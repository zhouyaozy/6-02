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

import java.util.Collections;
import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;

public class AppProperties {

  private final Map<String, Object> properties;

  private AppProperties(Map<String, Object> properties) {
    this.properties = Collections.unmodifiableMap(Maps.newHashMap(properties));
  }

  public static Builder builder() {
    return new Builder();
  }

  public String get(String key) {
    return get(key, null);
  }

  public String get(String key, String defaultValue) {
    Object value = properties.get(key);
    if (value != null) {
      return value.toString();
    }
    return defaultValue;
  }

  public int getInt(String key, int defaultValue) {
    Object value = properties.get(key);
    if (value != null) {
      return Integer.parseInt(value.toString());
    }
    return defaultValue;
  }

  public long getLong(String key, long defaultValue) {
    Object value = properties.get(key);
    if (value != null) {
      return Long.parseLong(value.toString());
    }
    return defaultValue;
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    Object value = properties.get(key);
    if (value != null) {
      return Boolean.parseBoolean(value.toString());
    }
    return defaultValue;
  }

  public double getDouble(String key, double defaultValue) {
    Object value = properties.get(key);
    if (value != null) {
      return Double.parseDouble(value.toString());
    }
    return defaultValue;
  }

  public Map<String, Object> asMap() {
    return properties;
  }

  public static class Builder {
    private final Map<String, Object> props = Maps.newHashMap();

    private Builder() {
    }

    public Builder with(String key, Object value) {
      Preconditions.checkNotNull(key, "Key cannot be null");
      props.put(key, value);
      return this;
    }

    public Builder withAll(Map<String, Object> properties) {
      Preconditions.checkNotNull(properties, "Properties cannot be null");
      props.putAll(properties);
      return this;
    }

    public AppProperties build() {
      return new AppProperties(props);
    }
  }
}