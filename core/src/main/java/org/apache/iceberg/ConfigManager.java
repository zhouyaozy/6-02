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

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;

public final class ConfigManager {

  public static final String PROP_DEFAULTS = "defaults";
  public static final String PROP_OVERRIDES = "overrides";
  public static final String PROP_DEFAULT_PREFIX = "config.default.";
  public static final String PROP_OVERRIDE_PREFIX = "config.override.";

  private final Map<String, String> defaults;
  private final Map<String, String> overrides;

  public static ConfigManager from(Map<String, ?> properties) {
    Preconditions.checkNotNull(properties, "Cannot initialize config manager from null properties");

    Map<String, String> defaults = extractConfigs(properties, PROP_DEFAULTS, PROP_DEFAULT_PREFIX);
    Map<String, String> overrides =
        extractConfigs(properties, PROP_OVERRIDES, PROP_OVERRIDE_PREFIX);
    return new ConfigManager(defaults, overrides);
  }

  public static ConfigManager from(Map<String, String> defaults, Map<String, String> overrides) {
    return new ConfigManager(defaults, overrides);
  }

  private ConfigManager(Map<String, String> defaults, Map<String, String> overrides) {
    this.defaults = Collections.unmodifiableMap(copyOf(defaults, "default"));
    this.overrides = Collections.unmodifiableMap(copyOf(overrides, "override"));
  }

  public Map<String, String> defaults() {
    return defaults;
  }

  public Map<String, String> overrides() {
    return overrides;
  }

  public Map<String, String> merge(Map<String, String> clientProperties) {
    Preconditions.checkNotNull(
        clientProperties,
        "Cannot merge client properties with managed properties. Invalid client configuration: null");

    Map<String, String> merged = Maps.newHashMap(defaults);
    merged.putAll(clientProperties);
    merged.putAll(overrides);
    return ImmutableMap.copyOf(Maps.filterValues(merged, Objects::nonNull));
  }

  private static Map<String, String> extractConfigs(
      Map<String, ?> properties, String propertyName, String propertyPrefix) {
    Map<String, String> configs = Maps.newHashMap();
    Object propertyValue = properties.get(propertyName);
    if (propertyValue != null) {
      Preconditions.checkArgument(
          propertyValue instanceof Map,
          "Invalid %s properties: expected a map but found %s",
          propertyName,
          propertyValue.getClass().getName());
      configs.putAll(copyOf((Map<?, ?>) propertyValue, propertyName));
    }

    for (Map.Entry<String, ?> entry : properties.entrySet()) {
      String key = entry.getKey();
      if (key != null && key.startsWith(propertyPrefix)) {
        configs.put(key.substring(propertyPrefix.length()), asString(entry.getValue()));
      }
    }

    return configs;
  }

  private static Map<String, String> copyOf(Map<?, ?> properties, String propertyType) {
    if (properties == null) {
      return Maps.newHashMap();
    }

    Map<String, String> copied = Maps.newHashMapWithExpectedSize(properties.size());
    for (Map.Entry<?, ?> entry : properties.entrySet()) {
      Object key = entry.getKey();
      Preconditions.checkArgument(key != null, "Invalid %s property: null", propertyType);
      copied.put(key.toString(), asString(entry.getValue()));
    }

    return copied;
  }

  private static String asString(Object value) {
    return value != null ? value.toString() : null;
  }
}
