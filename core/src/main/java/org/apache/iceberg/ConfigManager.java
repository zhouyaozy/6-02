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
package org.apache.iceberg;

import java.util.Map;

public class ConfigManager {

  private final Map<String, String> properties;

  public ConfigManager(Map<String, String> properties) {
    this.properties = properties;
  }

  public String getString(String key, String defaultValue) {
    return properties.getOrDefault(key, defaultValue);
  }

  public int getInt(String key, int defaultValue) {
    return Integer.parseInt(properties.getOrDefault(key, String.valueOf(defaultValue)));
  }

  public long getLong(String key, long defaultValue) {
    return Long.parseLong(properties.getOrDefault(key, String.valueOf(defaultValue)));
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    return Boolean.parseBoolean(properties.getOrDefault(key, String.valueOf(defaultValue)));
  }

  public double getDouble(String key, double defaultValue) {
    return Double.parseDouble(properties.getOrDefault(key, String.valueOf(defaultValue)));
  }

  public float getFloat(String key, float defaultValue) {
    return Float.parseFloat(properties.getOrDefault(key, String.valueOf(defaultValue)));
  }
}
