/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.properties;

import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

public class PropertyEntry<T> {

  private final String key;
  private final T defaultValue;

  private PropertyEntry(String key, T defaultValue) {
    Preconditions.checkNotNull(key, "Key cannot be null");
    this.key = key;
    this.defaultValue = defaultValue;
  }

  public static PropertyEntry<String> string(String key, String defaultValue) {
    return new PropertyEntry<>(key, defaultValue);
  }

  public static PropertyEntry<Integer> integer(String key, int defaultValue) {
    return new PropertyEntry<>(key, defaultValue);
  }

  public static PropertyEntry<Long> longEntry(String key, long defaultValue) {
    return new PropertyEntry<>(key, defaultValue);
  }

  public static PropertyEntry<Boolean> bool(String key, boolean defaultValue) {
    return new PropertyEntry<>(key, defaultValue);
  }

  public static PropertyEntry<Double> doubleEntry(String key, double defaultValue) {
    return new PropertyEntry<>(key, defaultValue);
  }

  public String key() {
    return key;
  }

  public T defaultValue() {
    return defaultValue;
  }
}