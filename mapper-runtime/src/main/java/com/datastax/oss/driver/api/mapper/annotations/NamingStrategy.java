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
package com.datastax.oss.driver.api.mapper.annotations;

import com.datastax.oss.driver.api.mapper.entity.naming.NameConverter;
import com.datastax.oss.driver.api.mapper.entity.naming.NamingConvention;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates an {@link Entity} to indicate how CQL names will be inferred from the names in the Java
 * class.
 *
 * <p>This applies to:
 *
 * <ul>
 *   <li>The name of the class (e.g. {@code Product}), that will be converted into a table name.
 *   <li>The name of the entity properties (e.g. {@code productId}), that will be converted into
 *       column names.
 * </ul>
 *
 * <p>Either of {@link #convention()} or {@link #customConverterClass()} must be specified, but not
 * both.
 *
 * <p>This annotation is optional. If it is not specified, the entity will default to {@link
 * NamingConvention#SNAKE_CASE_INSENSITIVE}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface NamingStrategy {

  /**
   * Specifies a built-in naming convention.
   *
   * <p>The mapper processor will apply the conversion at compile time, and generate code that
   * hard-codes the converted strings. In other words, this is slightly more efficient than a custom
   * converter because no conversion happens at runtime.
   *
   * <p>This is mutually exclusive with {@link #customConverterClass()}.
   *
   * <p>Note that, for technical reasons, this is an array, but only one element is expected. If you
   * specify more than one element, the mapper processor will generate a compile-time warning, and
   * proceed with the first one.
   */
  NamingConvention[] convention() default {};

  /**
   * Specifies a custom converter implementation.
   *
   * <p>The provided class must implement {@link NameConverter} and expose a public no-arg
   * constructor. The code generated by the mapper will create an instance at runtime, and invoke it
   * every time it generates a new request.
   *
   * <p>This is mutually exclusive with {@link #convention()}.
   *
   * <p>Note that, for technical reasons, this is an array, but only one element is expected. If you
   * specify more than one element, the mapper processor will generate a compile-time warning, and
   * proceed with the first one.
   */
  Class<? extends NameConverter>[] customConverterClass() default {};
}
