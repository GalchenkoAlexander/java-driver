/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.core.cql;

import com.datastax.oss.driver.api.core.AllNodesFailedException;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.RequestThrottlingException;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.connection.FrameTooLongException;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metrics.DefaultNodeMetric;
import com.datastax.oss.driver.api.core.metrics.DefaultSessionMetric;
import com.datastax.oss.driver.api.core.retry.RetryDecision;
import com.datastax.oss.driver.api.core.retry.RetryPolicy;
import com.datastax.oss.driver.api.core.servererrors.BootstrappingException;
import com.datastax.oss.driver.api.core.servererrors.CoordinatorException;
import com.datastax.oss.driver.api.core.servererrors.FunctionFailureException;
import com.datastax.oss.driver.api.core.servererrors.ProtocolError;
import com.datastax.oss.driver.api.core.servererrors.QueryValidationException;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.datastax.oss.driver.api.core.session.throttling.RequestThrottler;
import com.datastax.oss.driver.api.core.session.throttling.Throttled;
import com.datastax.oss.driver.api.core.specex.SpeculativeExecutionPolicy;
import com.datastax.oss.driver.api.core.tracker.RequestTracker;
import com.datastax.oss.driver.internal.core.adminrequest.ThrottledAdminRequestHandler;
import com.datastax.oss.driver.internal.core.adminrequest.UnexpectedResponseException;
import com.datastax.oss.driver.internal.core.channel.DriverChannel;
import com.datastax.oss.driver.internal.core.channel.ResponseCallback;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.internal.core.metadata.DefaultNode;
import com.datastax.oss.driver.internal.core.metrics.NodeMetricUpdater;
import com.datastax.oss.driver.internal.core.metrics.SessionMetricUpdater;
import com.datastax.oss.driver.internal.core.session.DefaultSession;
import com.datastax.oss.driver.internal.core.session.RepreparePayload;
import com.datastax.oss.driver.internal.core.tracker.NoopRequestTracker;
import com.datastax.oss.driver.internal.core.tracker.RequestLogger;
import com.datastax.oss.driver.internal.core.util.Loggers;
import com.datastax.oss.driver.internal.core.util.collection.QueryPlan;
import com.datastax.oss.protocol.internal.Frame;
import com.datastax.oss.protocol.internal.Message;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import com.datastax.oss.protocol.internal.request.Prepare;
import com.datastax.oss.protocol.internal.response.Error;
import com.datastax.oss.protocol.internal.response.Result;
import com.datastax.oss.protocol.internal.response.error.Unprepared;
import com.datastax.oss.protocol.internal.response.result.Rows;
import com.datastax.oss.protocol.internal.response.result.SchemaChange;
import com.datastax.oss.protocol.internal.response.result.SetKeyspace;
import com.datastax.oss.protocol.internal.response.result.Void;
import com.datastax.oss.protocol.internal.util.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.netty.handler.codec.EncoderException;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jcip.annotations.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
public class CqlRequestHandler implements Throttled {

  private static final Logger LOG = LoggerFactory.getLogger(CqlRequestHandler.class);
  private static final long NANOTIME_NOT_MEASURED_YET = -1;

  private final long startTimeNanos;
  private final String logPrefix;
  private final Statement<?> statement;
  private final DefaultSession session;
  private final CqlIdentifier keyspace;
  private final InternalDriverContext context;
  @NonNull private final DriverExecutionProfile executionProfile;
  private final boolean isIdempotent;
  protected final CompletableFuture<AsyncResultSet> result;
  private final Message message;
  private final Timer timer;
  /**
   * How many speculative executions are currently running (including the initial execution). We
   * track this in order to know when to fail the request if all executions have reached the end of
   * the query plan.
   */
  private final AtomicInteger activeExecutionsCount;
  /**
   * How many speculative executions have started (excluding the initial execution), whether they
   * have completed or not. We track this in order to fill {@link
   * ExecutionInfo#getSpeculativeExecutionCount()}.
   */
  private final AtomicInteger startedSpeculativeExecutionsCount;

  private final Duration timeout;
  final Timeout scheduledTimeout;
  final List<Timeout> scheduledExecutions;
  private final List<NodeResponseCallback> inFlightCallbacks;
  private final RetryPolicy retryPolicy;
  private final SpeculativeExecutionPolicy speculativeExecutionPolicy;
  private final RequestThrottler throttler;
  private final RequestTracker requestTracker;
  private final SessionMetricUpdater sessionMetricUpdater;

  // The errors on the nodes that were already tried (lazily initialized on the first error).
  // We don't use a map because nodes can appear multiple times.
  private volatile List<Map.Entry<Node, Throwable>> errors;

  protected CqlRequestHandler(
      Statement<?> statement,
      DefaultSession session,
      InternalDriverContext context,
      String sessionLogPrefix) {

    this.startTimeNanos = System.nanoTime();
    this.logPrefix = sessionLogPrefix + "|" + this.hashCode();
    LOG.trace("[{}] Creating new handler for request {}", logPrefix, statement);

    this.statement = statement;
    this.session = session;
    this.keyspace = session.getKeyspace().orElse(null);
    this.context = context;
    this.executionProfile = Conversions.resolveExecutionProfile(statement, context);
    this.retryPolicy = context.getRetryPolicy(executionProfile.getName());
    this.speculativeExecutionPolicy =
        context.getSpeculativeExecutionPolicy(executionProfile.getName());
    Boolean statementIsIdempotent = statement.isIdempotent();
    this.isIdempotent =
        (statementIsIdempotent == null)
            ? executionProfile.getBoolean(DefaultDriverOption.REQUEST_DEFAULT_IDEMPOTENCE)
            : statementIsIdempotent;
    this.result = new CompletableFuture<>();
    this.result.exceptionally(
        t -> {
          try {
            if (t instanceof CancellationException) {
              cancelScheduledTasks();
            }
          } catch (Throwable t2) {
            Loggers.warnWithException(LOG, "[{}] Uncaught exception", logPrefix, t2);
          }
          return null;
        });
    this.message = Conversions.toMessage(statement, executionProfile, context);
    this.timer = context.getNettyOptions().getTimer();

    this.timeout =
        statement.getTimeout() != null
            ? statement.getTimeout()
            : executionProfile.getDuration(DefaultDriverOption.REQUEST_TIMEOUT);
    this.scheduledTimeout = scheduleTimeout(timeout);

    this.activeExecutionsCount = new AtomicInteger(1);
    this.startedSpeculativeExecutionsCount = new AtomicInteger(0);
    this.scheduledExecutions = isIdempotent ? new CopyOnWriteArrayList<>() : null;
    this.inFlightCallbacks = new CopyOnWriteArrayList<>();

    this.requestTracker = context.getRequestTracker();
    this.sessionMetricUpdater = session.getMetricUpdater();

    this.throttler = context.getRequestThrottler();
    this.throttler.register(this);
  }

  @Override
  public void onThrottleReady(boolean wasDelayed) {
    if (wasDelayed
        // avoid call to nanoTime() if metric is disabled:
        && sessionMetricUpdater.isEnabled(
            DefaultSessionMetric.THROTTLING_DELAY, executionProfile.getName())) {
      sessionMetricUpdater.updateTimer(
          DefaultSessionMetric.THROTTLING_DELAY,
          executionProfile.getName(),
          System.nanoTime() - startTimeNanos,
          TimeUnit.NANOSECONDS);
    }
    Queue<Node> queryPlan =
        this.statement.getNode() != null
            ? new QueryPlan(this.statement.getNode())
            : context
                .getLoadBalancingPolicyWrapper()
                .newQueryPlan(statement, executionProfile.getName(), session);
    sendRequest(null, queryPlan, 0, 0, true);
  }

  public CompletionStage<AsyncResultSet> handle() {
    return result;
  }

  private Timeout scheduleTimeout(Duration timeoutDuration) {
    if (timeoutDuration.toNanos() > 0) {
      try {
        return this.timer.newTimeout(
            (Timeout timeout1) ->
                setFinalError(
                    new DriverTimeoutException("Query timed out after " + timeoutDuration),
                    null,
                    -1),
            timeoutDuration.toNanos(),
            TimeUnit.NANOSECONDS);
      } catch (IllegalStateException e) {
        // If we raced with session shutdown the timer might be closed already, rethrow with a more
        // explicit message
        result.completeExceptionally(
            ("cannot be started once stopped".equals(e.getMessage()))
                ? new IllegalStateException("Session is closed")
                : e);
      }
    }
    return null;
  }

  /**
   * Sends the request to the next available node.
   *
   * @param retriedNode if not null, it will be attempted first before the rest of the query plan.
   * @param queryPlan the list of nodes to try (shared with all other executions)
   * @param currentExecutionIndex 0 for the initial execution, 1 for the first speculative one, etc.
   * @param retryCount the number of times that the retry policy was invoked for this execution
   *     already (note that some internal retries don't go through the policy, and therefore don't
   *     increment this counter)
   * @param scheduleNextExecution whether to schedule the next speculative execution
   */
  private void sendRequest(
      Node retriedNode,
      Queue<Node> queryPlan,
      int currentExecutionIndex,
      int retryCount,
      boolean scheduleNextExecution) {
    if (result.isDone()) {
      return;
    }
    Node node = retriedNode;
    DriverChannel channel = null;
    if (node == null || (channel = session.getChannel(node, logPrefix)) == null) {
      while (!result.isDone() && (node = queryPlan.poll()) != null) {
        channel = session.getChannel(node, logPrefix);
        if (channel != null) {
          break;
        }
      }
    }
    if (channel == null) {
      // We've reached the end of the query plan without finding any node to write to
      if (!result.isDone() && activeExecutionsCount.decrementAndGet() == 0) {
        // We're the last execution so fail the result
        setFinalError(AllNodesFailedException.fromErrors(this.errors), null, -1);
      }
    } else {
      NodeResponseCallback nodeResponseCallback =
          new NodeResponseCallback(
              node,
              queryPlan,
              channel,
              currentExecutionIndex,
              retryCount,
              scheduleNextExecution,
              logPrefix);
      channel
          .write(message, statement.isTracing(), statement.getCustomPayload(), nodeResponseCallback)
          .addListener(nodeResponseCallback);
    }
  }

  private void recordError(Node node, Throwable error) {
    // Use a local variable to do only a single single volatile read in the nominal case
    List<Map.Entry<Node, Throwable>> errorsSnapshot = this.errors;
    if (errorsSnapshot == null) {
      synchronized (CqlRequestHandler.this) {
        errorsSnapshot = this.errors;
        if (errorsSnapshot == null) {
          this.errors = errorsSnapshot = new CopyOnWriteArrayList<>();
        }
      }
    }
    errorsSnapshot.add(new AbstractMap.SimpleEntry<>(node, error));
  }

  private void cancelScheduledTasks() {
    if (this.scheduledTimeout != null) {
      this.scheduledTimeout.cancel();
    }
    if (scheduledExecutions != null) {
      for (Timeout scheduledExecution : scheduledExecutions) {
        scheduledExecution.cancel();
      }
    }
    for (NodeResponseCallback callback : inFlightCallbacks) {
      callback.cancel();
    }
  }

  private void setFinalResult(
      Result resultMessage,
      Frame responseFrame,
      boolean schemaInAgreement,
      NodeResponseCallback callback) {
    try {
      ExecutionInfo executionInfo =
          buildExecutionInfo(callback, resultMessage, responseFrame, schemaInAgreement);
      AsyncResultSet resultSet =
          Conversions.toResultSet(resultMessage, executionInfo, session, context);
      if (result.complete(resultSet)) {
        cancelScheduledTasks();
        throttler.signalSuccess(this);

        // Only call nanoTime() if we're actually going to use it
        long completionTimeNanos = NANOTIME_NOT_MEASURED_YET,
            totalLatencyNanos = NANOTIME_NOT_MEASURED_YET;
        if (!(requestTracker instanceof NoopRequestTracker)) {
          completionTimeNanos = System.nanoTime();
          totalLatencyNanos = completionTimeNanos - startTimeNanos;
          long nodeLatencyNanos = completionTimeNanos - callback.nodeStartTimeNanos;
          requestTracker.onNodeSuccess(
              statement, nodeLatencyNanos, executionProfile, callback.node, logPrefix);
          requestTracker.onSuccess(
              statement, totalLatencyNanos, executionProfile, callback.node, logPrefix);
        }
        if (sessionMetricUpdater.isEnabled(
            DefaultSessionMetric.CQL_REQUESTS, executionProfile.getName())) {
          if (completionTimeNanos == NANOTIME_NOT_MEASURED_YET) {
            completionTimeNanos = System.nanoTime();
            totalLatencyNanos = completionTimeNanos - startTimeNanos;
          }
          sessionMetricUpdater.updateTimer(
              DefaultSessionMetric.CQL_REQUESTS,
              executionProfile.getName(),
              totalLatencyNanos,
              TimeUnit.NANOSECONDS);
        }
      }
      // log the warnings if they have NOT been disabled
      if (!executionInfo.getWarnings().isEmpty()
          && executionProfile.getBoolean(DefaultDriverOption.REQUEST_LOG_WARNINGS)
          && LOG.isWarnEnabled()) {
        logServerWarnings(executionInfo.getWarnings());
      }
    } catch (Throwable error) {
      setFinalError(error, callback.node, -1);
    }
  }

  private void logServerWarnings(List<String> warnings) {
    // use the RequestLogFormatter to format the query
    StringBuilder statementString = new StringBuilder();
    context
        .getRequestLogFormatter()
        .appendRequest(
            statement,
            executionProfile.getInt(
                DefaultDriverOption.REQUEST_LOGGER_MAX_QUERY_LENGTH,
                RequestLogger.DEFAULT_REQUEST_LOGGER_MAX_QUERY_LENGTH),
            executionProfile.getBoolean(
                DefaultDriverOption.REQUEST_LOGGER_VALUES,
                RequestLogger.DEFAULT_REQUEST_LOGGER_SHOW_VALUES),
            executionProfile.getInt(
                DefaultDriverOption.REQUEST_LOGGER_MAX_VALUES,
                RequestLogger.DEFAULT_REQUEST_LOGGER_MAX_VALUES),
            executionProfile.getInt(
                DefaultDriverOption.REQUEST_LOGGER_MAX_VALUE_LENGTH,
                RequestLogger.DEFAULT_REQUEST_LOGGER_MAX_VALUE_LENGTH),
            statementString);
    // log each warning separately
    warnings.forEach(
        (warning) ->
            LOG.warn("Query '{}' generated server side warning(s): {}", statementString, warning));
  }

  private ExecutionInfo buildExecutionInfo(
      NodeResponseCallback callback,
      Result resultMessage,
      Frame responseFrame,
      boolean schemaInAgreement) {
    ByteBuffer pagingState =
        (resultMessage instanceof Rows) ? ((Rows) resultMessage).getMetadata().pagingState : null;
    return new DefaultExecutionInfo(
        statement,
        callback.node,
        startedSpeculativeExecutionsCount.get(),
        callback.execution,
        errors,
        pagingState,
        responseFrame,
        schemaInAgreement,
        session,
        context,
        executionProfile);
  }

  @Override
  public void onThrottleFailure(@NonNull RequestThrottlingException error) {
    sessionMetricUpdater.incrementCounter(
        DefaultSessionMetric.THROTTLING_ERRORS, executionProfile.getName());
    setFinalError(error, null, -1);
  }

  private void setFinalError(Throwable error, Node node, int execution) {
    if (error instanceof DriverException) {
      ((DriverException) error)
          .setExecutionInfo(
              new DefaultExecutionInfo(
                  statement,
                  node,
                  startedSpeculativeExecutionsCount.get(),
                  execution,
                  errors,
                  null,
                  null,
                  true,
                  session,
                  context,
                  executionProfile));
    }
    if (result.completeExceptionally(error)) {
      cancelScheduledTasks();
      if (!(requestTracker instanceof NoopRequestTracker)) {
        long latencyNanos = System.nanoTime() - startTimeNanos;
        requestTracker.onError(statement, error, latencyNanos, executionProfile, node, logPrefix);
      }
      if (error instanceof DriverTimeoutException) {
        throttler.signalTimeout(this);
        sessionMetricUpdater.incrementCounter(
            DefaultSessionMetric.CQL_CLIENT_TIMEOUTS, executionProfile.getName());
      } else if (!(error instanceof RequestThrottlingException)) {
        throttler.signalError(this, error);
      }
    }
  }

  /**
   * Handles the interaction with a single node in the query plan.
   *
   * <p>An instance of this class is created each time we (re)try a node.
   */
  private class NodeResponseCallback
      implements ResponseCallback, GenericFutureListener<Future<java.lang.Void>> {

    private final long nodeStartTimeNanos = System.nanoTime();
    private final Node node;
    private final Queue<Node> queryPlan;
    private final DriverChannel channel;
    // The identifier of the current execution (0 for the initial execution, 1 for the first
    // speculative execution, etc.)
    private final int execution;
    // How many times we've invoked the retry policy and it has returned a "retry" decision (0 for
    // the first attempt of each execution).
    private final int retryCount;
    private final boolean scheduleNextExecution;
    private final String logPrefix;

    private NodeResponseCallback(
        Node node,
        Queue<Node> queryPlan,
        DriverChannel channel,
        int execution,
        int retryCount,
        boolean scheduleNextExecution,
        String logPrefix) {
      this.node = node;
      this.queryPlan = queryPlan;
      this.channel = channel;
      this.execution = execution;
      this.retryCount = retryCount;
      this.scheduleNextExecution = scheduleNextExecution;
      this.logPrefix = logPrefix + "|" + execution;
    }

    // this gets invoked once the write completes.
    @Override
    public void operationComplete(Future<java.lang.Void> future) throws Exception {
      if (!future.isSuccess()) {
        Throwable error = future.cause();
        if (error instanceof EncoderException
            && error.getCause() instanceof FrameTooLongException) {
          trackNodeError(node, error.getCause(), NANOTIME_NOT_MEASURED_YET);
          setFinalError(error.getCause(), node, execution);
        } else {
          LOG.trace(
              "[{}] Failed to send request on {}, trying next node (cause: {})",
              logPrefix,
              channel,
              error);
          recordError(node, error);
          trackNodeError(node, error, NANOTIME_NOT_MEASURED_YET);
          ((DefaultNode) node)
              .getMetricUpdater()
              .incrementCounter(DefaultNodeMetric.UNSENT_REQUESTS, executionProfile.getName());
          sendRequest(
              null, queryPlan, execution, retryCount, scheduleNextExecution); // try next node
        }
      } else {
        LOG.trace("[{}] Request sent on {}", logPrefix, channel);
        if (result.isDone()) {
          // If the handler completed since the last time we checked, cancel directly because we
          // don't know if cancelScheduledTasks() has run yet
          cancel();
        } else {
          inFlightCallbacks.add(this);
          if (scheduleNextExecution && isIdempotent) {
            int nextExecution = execution + 1;
            long nextDelay =
                speculativeExecutionPolicy.nextExecution(node, keyspace, statement, nextExecution);
            if (nextDelay >= 0) {
              scheduleSpeculativeExecution(nextExecution, nextDelay);
            } else {
              LOG.trace(
                  "[{}] Speculative execution policy returned {}, no next execution",
                  logPrefix,
                  nextDelay);
            }
          }
        }
      }
    }

    private void scheduleSpeculativeExecution(int index, long delay) {
      LOG.trace("[{}] Scheduling speculative execution {} in {} ms", logPrefix, index, delay);
      try {
        scheduledExecutions.add(
            timer.newTimeout(
                (Timeout timeout1) -> {
                  if (!result.isDone()) {
                    LOG.trace(
                        "[{}] Starting speculative execution {}",
                        CqlRequestHandler.this.logPrefix,
                        index);
                    activeExecutionsCount.incrementAndGet();
                    startedSpeculativeExecutionsCount.incrementAndGet();
                    // Note that `node` is the first node of the execution, it might not be the
                    // "slow" one if there were retries, but in practice retries are rare.
                    ((DefaultNode) node)
                        .getMetricUpdater()
                        .incrementCounter(
                            DefaultNodeMetric.SPECULATIVE_EXECUTIONS, executionProfile.getName());
                    sendRequest(null, queryPlan, index, 0, true);
                  }
                },
                delay,
                TimeUnit.MILLISECONDS));
      } catch (IllegalStateException e) {
        // If we're racing with session shutdown, the timer might be stopped already. We don't want
        // to schedule more executions anyway, so swallow the error.
        if (!"cannot be started once stopped".equals(e.getMessage())) {
          Loggers.warnWithException(
              LOG, "[{}] Error while scheduling speculative execution", logPrefix, e);
        }
      }
    }

    @Override
    public void onResponse(Frame responseFrame) {
      long nodeResponseTimeNanos = NANOTIME_NOT_MEASURED_YET;
      NodeMetricUpdater nodeMetricUpdater = ((DefaultNode) node).getMetricUpdater();
      if (nodeMetricUpdater.isEnabled(DefaultNodeMetric.CQL_MESSAGES, executionProfile.getName())) {
        nodeResponseTimeNanos = System.nanoTime();
        long nodeLatency = System.nanoTime() - nodeStartTimeNanos;
        nodeMetricUpdater.updateTimer(
            DefaultNodeMetric.CQL_MESSAGES,
            executionProfile.getName(),
            nodeLatency,
            TimeUnit.NANOSECONDS);
      }
      inFlightCallbacks.remove(this);
      if (result.isDone()) {
        return;
      }
      try {
        Message responseMessage = responseFrame.message;
        if (responseMessage instanceof SchemaChange) {
          SchemaChange schemaChange = (SchemaChange) responseMessage;
          context
              .getTopologyMonitor()
              .checkSchemaAgreement()
              .thenCombine(
                  context
                      .getMetadataManager()
                      .refreshSchema(schemaChange.keyspace, false, false)
                      .exceptionally(
                          error -> {
                            Loggers.warnWithException(
                                LOG,
                                "[{}] Error while refreshing schema after DDL query, "
                                    + "new metadata might be incomplete",
                                logPrefix,
                                error);
                            return null;
                          }),
                  (schemaInAgreement, metadata) -> schemaInAgreement)
              .whenComplete(
                  ((schemaInAgreement, error) ->
                      setFinalResult(schemaChange, responseFrame, schemaInAgreement, this)));
        } else if (responseMessage instanceof SetKeyspace) {
          SetKeyspace setKeyspace = (SetKeyspace) responseMessage;
          session
              .setKeyspace(CqlIdentifier.fromInternal(setKeyspace.keyspace))
              .whenComplete((v, error) -> setFinalResult(setKeyspace, responseFrame, true, this));
        } else if (responseMessage instanceof Result) {
          LOG.trace("[{}] Got result, completing", logPrefix);
          setFinalResult((Result) responseMessage, responseFrame, true, this);
        } else if (responseMessage instanceof Error) {
          LOG.trace("[{}] Got error response, processing", logPrefix);
          processErrorResponse((Error) responseMessage);
        } else {
          trackNodeError(
              node,
              new IllegalStateException("Unexpected response " + responseMessage),
              nodeResponseTimeNanos);
          setFinalError(
              new IllegalStateException("Unexpected response " + responseMessage), node, execution);
        }
      } catch (Throwable t) {
        trackNodeError(node, t, nodeResponseTimeNanos);
        setFinalError(t, node, execution);
      }
    }

    private void processErrorResponse(Error errorMessage) {
      if (errorMessage.code == ProtocolConstants.ErrorCode.UNPREPARED) {
        LOG.trace("[{}] Statement is not prepared on {}, repreparing", logPrefix, node);
        ByteBuffer id = ByteBuffer.wrap(((Unprepared) errorMessage).id);
        RepreparePayload repreparePayload = session.getRepreparePayloads().get(id);
        if (repreparePayload == null) {
          throw new IllegalStateException(
              String.format(
                  "Tried to execute unprepared query %s but we don't have the data to reprepare it",
                  Bytes.toHexString(id)));
        }
        Prepare reprepareMessage = new Prepare(repreparePayload.query);
        ThrottledAdminRequestHandler reprepareHandler =
            new ThrottledAdminRequestHandler(
                channel,
                reprepareMessage,
                repreparePayload.customPayload,
                timeout,
                throttler,
                sessionMetricUpdater,
                logPrefix,
                "Reprepare " + reprepareMessage.toString());
        reprepareHandler
            .start()
            .handle(
                (result, exception) -> {
                  if (exception != null) {
                    // If the error is not recoverable, surface it to the client instead of retrying
                    if (exception instanceof UnexpectedResponseException) {
                      Message prepareErrorMessage =
                          ((UnexpectedResponseException) exception).message;
                      if (prepareErrorMessage instanceof Error) {
                        CoordinatorException prepareError =
                            Conversions.toThrowable(node, (Error) prepareErrorMessage, context);
                        if (prepareError instanceof QueryValidationException
                            || prepareError instanceof FunctionFailureException
                            || prepareError instanceof ProtocolError) {
                          LOG.trace("[{}] Unrecoverable error on reprepare, rethrowing", logPrefix);
                          trackNodeError(node, prepareError, NANOTIME_NOT_MEASURED_YET);
                          setFinalError(prepareError, node, execution);
                          return null;
                        }
                      }
                    } else if (exception instanceof RequestThrottlingException) {
                      setFinalError(exception, node, execution);
                      return null;
                    }
                    recordError(node, exception);
                    trackNodeError(node, exception, NANOTIME_NOT_MEASURED_YET);
                    LOG.trace("[{}] Reprepare failed, trying next node", logPrefix);
                    sendRequest(null, queryPlan, execution, retryCount, false);
                  } else {
                    LOG.trace("[{}] Reprepare sucessful, retrying", logPrefix);
                    sendRequest(node, queryPlan, execution, retryCount, false);
                  }
                  return null;
                });
        return;
      }
      CoordinatorException error = Conversions.toThrowable(node, errorMessage, context);
      NodeMetricUpdater metricUpdater = ((DefaultNode) node).getMetricUpdater();
      if (error instanceof BootstrappingException) {
        LOG.trace("[{}] {} is bootstrapping, trying next node", logPrefix, node);
        recordError(node, error);
        trackNodeError(node, error, NANOTIME_NOT_MEASURED_YET);
        sendRequest(null, queryPlan, execution, retryCount, false);
      } else if (error instanceof QueryValidationException
          || error instanceof FunctionFailureException
          || error instanceof ProtocolError) {
        LOG.trace("[{}] Unrecoverable error, rethrowing", logPrefix);
        metricUpdater.incrementCounter(DefaultNodeMetric.OTHER_ERRORS, executionProfile.getName());
        trackNodeError(node, error, NANOTIME_NOT_MEASURED_YET);
        setFinalError(error, node, execution);
      } else {
        RetryDecision decision;
        if (error instanceof ReadTimeoutException) {
          ReadTimeoutException readTimeout = (ReadTimeoutException) error;
          decision =
              retryPolicy.onReadTimeout(
                  statement,
                  readTimeout.getConsistencyLevel(),
                  readTimeout.getBlockFor(),
                  readTimeout.getReceived(),
                  readTimeout.wasDataPresent(),
                  retryCount);
          updateErrorMetrics(
              metricUpdater,
              decision,
              DefaultNodeMetric.READ_TIMEOUTS,
              DefaultNodeMetric.RETRIES_ON_READ_TIMEOUT,
              DefaultNodeMetric.IGNORES_ON_READ_TIMEOUT);
        } else if (error instanceof WriteTimeoutException) {
          WriteTimeoutException writeTimeout = (WriteTimeoutException) error;
          decision =
              isIdempotent
                  ? retryPolicy.onWriteTimeout(
                      statement,
                      writeTimeout.getConsistencyLevel(),
                      writeTimeout.getWriteType(),
                      writeTimeout.getBlockFor(),
                      writeTimeout.getReceived(),
                      retryCount)
                  : RetryDecision.RETHROW;
          updateErrorMetrics(
              metricUpdater,
              decision,
              DefaultNodeMetric.WRITE_TIMEOUTS,
              DefaultNodeMetric.RETRIES_ON_WRITE_TIMEOUT,
              DefaultNodeMetric.IGNORES_ON_WRITE_TIMEOUT);
        } else if (error instanceof UnavailableException) {
          UnavailableException unavailable = (UnavailableException) error;
          decision =
              retryPolicy.onUnavailable(
                  statement,
                  unavailable.getConsistencyLevel(),
                  unavailable.getRequired(),
                  unavailable.getAlive(),
                  retryCount);
          updateErrorMetrics(
              metricUpdater,
              decision,
              DefaultNodeMetric.UNAVAILABLES,
              DefaultNodeMetric.RETRIES_ON_UNAVAILABLE,
              DefaultNodeMetric.IGNORES_ON_UNAVAILABLE);
        } else {
          decision =
              isIdempotent
                  ? retryPolicy.onErrorResponse(statement, error, retryCount)
                  : RetryDecision.RETHROW;
          updateErrorMetrics(
              metricUpdater,
              decision,
              DefaultNodeMetric.OTHER_ERRORS,
              DefaultNodeMetric.RETRIES_ON_OTHER_ERROR,
              DefaultNodeMetric.IGNORES_ON_OTHER_ERROR);
        }
        processRetryDecision(decision, error);
      }
    }

    private void processRetryDecision(RetryDecision decision, Throwable error) {
      LOG.trace("[{}] Processing retry decision {}", logPrefix, decision);
      switch (decision) {
        case RETRY_SAME:
          recordError(node, error);
          trackNodeError(node, error, NANOTIME_NOT_MEASURED_YET);
          sendRequest(node, queryPlan, execution, retryCount + 1, false);
          break;
        case RETRY_NEXT:
          recordError(node, error);
          trackNodeError(node, error, NANOTIME_NOT_MEASURED_YET);
          sendRequest(null, queryPlan, execution, retryCount + 1, false);
          break;
        case RETHROW:
          trackNodeError(node, error, NANOTIME_NOT_MEASURED_YET);
          setFinalError(error, node, execution);
          break;
        case IGNORE:
          setFinalResult(Void.INSTANCE, null, true, this);
          break;
      }
    }

    private void updateErrorMetrics(
        NodeMetricUpdater metricUpdater,
        RetryDecision decision,
        DefaultNodeMetric error,
        DefaultNodeMetric retriesOnError,
        DefaultNodeMetric ignoresOnError) {
      metricUpdater.incrementCounter(error, executionProfile.getName());
      switch (decision) {
        case RETRY_SAME:
        case RETRY_NEXT:
          metricUpdater.incrementCounter(DefaultNodeMetric.RETRIES, executionProfile.getName());
          metricUpdater.incrementCounter(retriesOnError, executionProfile.getName());
          break;
        case IGNORE:
          metricUpdater.incrementCounter(DefaultNodeMetric.IGNORES, executionProfile.getName());
          metricUpdater.incrementCounter(ignoresOnError, executionProfile.getName());
          break;
        case RETHROW:
          // nothing do do
      }
    }

    @Override
    public void onFailure(Throwable error) {
      inFlightCallbacks.remove(this);
      if (result.isDone()) {
        return;
      }
      LOG.trace("[{}] Request failure, processing: {}", logPrefix, error.toString());
      RetryDecision decision;
      if (!isIdempotent || error instanceof FrameTooLongException) {
        decision = RetryDecision.RETHROW;
      } else {
        decision = retryPolicy.onRequestAborted(statement, error, retryCount);
      }
      processRetryDecision(decision, error);
      updateErrorMetrics(
          ((DefaultNode) node).getMetricUpdater(),
          decision,
          DefaultNodeMetric.ABORTED_REQUESTS,
          DefaultNodeMetric.RETRIES_ON_ABORTED,
          DefaultNodeMetric.IGNORES_ON_ABORTED);
    }

    public void cancel() {
      try {
        if (!channel.closeFuture().isDone()) {
          this.channel.cancel(this);
        }
      } catch (Throwable t) {
        Loggers.warnWithException(LOG, "[{}] Error cancelling", logPrefix, t);
      }
    }

    /**
     * @param nodeResponseTimeNanos the time we received the response, if it's already been
     *     measured. If {@link #NANOTIME_NOT_MEASURED_YET}, it hasn't and we need to measure it now
     *     (this is to avoid unnecessary calls to System.nanoTime)
     */
    private void trackNodeError(Node node, Throwable error, long nodeResponseTimeNanos) {
      if (requestTracker instanceof NoopRequestTracker) {
        return;
      }
      if (nodeResponseTimeNanos == NANOTIME_NOT_MEASURED_YET) {
        nodeResponseTimeNanos = System.nanoTime();
      }
      long latencyNanos = nodeResponseTimeNanos - this.nodeStartTimeNanos;
      requestTracker.onNodeError(statement, error, latencyNanos, executionProfile, node, logPrefix);
    }

    @Override
    public String toString() {
      return logPrefix;
    }
  }
}
