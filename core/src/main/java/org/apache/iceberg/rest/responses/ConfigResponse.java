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
package org.apache.iceberg.rest.responses;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.iceberg.ConfigManager;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.rest.Endpoint;
import org.apache.iceberg.rest.RESTResponse;

/**
 * Represents a response to requesting server-side provided configuration for the REST catalog. This
 * allows client provided values to be overridden by the server or defaulted if not provided by the
 * client.
 *
 * <p>The catalog properties, with overrides and defaults applied, should be used to configure the
 * catalog and for all subsequent requests after this initial config request.
 *
 * <p>Configuration from the server consists of two sets of key/value pairs.
 *
 * <ul>
 *   <li>defaults - properties that should be used as default configuration
 *   <li>overrides - properties that should be used to override client configuration
 *   <li>endpoints - a list of endpoints that the server supports
 * </ul>
 */
public class ConfigResponse implements RESTResponse {

  private Map<String, String> defaults;
  private Map<String, String> overrides;
  private List<Endpoint> endpoints;
  private String idempotencyKeyLifetime;

  public ConfigResponse() {
    // Required for Jackson deserialization
  }

  private ConfigResponse(
      Map<String, String> defaults,
      Map<String, String> overrides,
      List<Endpoint> endpoints,
      String idempotencyKeyLifetime) {
    this.defaults = defaults;
    this.overrides = overrides;
    this.endpoints = endpoints;
    this.idempotencyKeyLifetime = idempotencyKeyLifetime;
    validate();
  }

  @Override
  public void validate() {}

  public Map<String, String> defaults() {
    return defaults != null ? defaults : ImmutableMap.of();
  }

  public Map<String, String> overrides() {
    return overrides != null ? overrides : ImmutableMap.of();
  }

  public List<Endpoint> endpoints() {
    return null != endpoints ? endpoints : ImmutableList.of();
  }

  @Nullable
  public String idempotencyKeyLifetime() {
    return idempotencyKeyLifetime;
  }

  public Map<String, String> merge(Map<String, String> clientProperties) {
    Preconditions.checkNotNull(
        clientProperties,
        "Cannot merge client properties with server-provided properties. Invalid client configuration: null");
    return ConfigManager.from(defaults(), overrides()).merge(clientProperties);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("defaults", defaults)
        .add("overrides", overrides)
        .add("endpoints", endpoints)
        .add("idempotencyKeyLifetime", idempotencyKeyLifetime)
        .toString();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final Map<String, String> defaults;
    private final Map<String, String> overrides;
    private final List<Endpoint> endpoints;
    private String idempotencyKeyLifetime;

    private Builder() {
      this.defaults = Maps.newHashMap();
      this.overrides = Maps.newHashMap();
      this.endpoints = Lists.newArrayList();
      this.idempotencyKeyLifetime = null;
    }

    public Builder withDefault(String key, String value) {
      Preconditions.checkNotNull(key, "Invalid default property: null");
      defaults.put(key, value);
      return this;
    }

    public Builder withOverride(String key, String value) {
      Preconditions.checkNotNull(key, "Invalid override property: null");
      overrides.put(key, value);
      return this;
    }

    public Builder withDefaults(Map<String, String> defaultsToAdd) {
      Preconditions.checkNotNull(defaultsToAdd, "Invalid default properties map: null");
      Preconditions.checkArgument(
          !defaultsToAdd.containsKey(null), "Invalid default property: null");
      defaults.putAll(defaultsToAdd);
      return this;
    }

    public Builder withOverrides(Map<String, String> overridesToAdd) {
      Preconditions.checkNotNull(overridesToAdd, "Invalid override properties map: null");
      Preconditions.checkArgument(
          !overridesToAdd.containsKey(null), "Invalid override property: null");
      overrides.putAll(overridesToAdd);
      return this;
    }

    public Builder withEndpoints(List<Endpoint> endpointsToAdd) {
      endpoints.addAll(endpointsToAdd);
      return this;
    }

    public Builder withIdempotencyKeyLifetime(String lifetime) {
      this.idempotencyKeyLifetime = lifetime;
      return this;
    }

    public ConfigResponse build() {
      return new ConfigResponse(defaults, overrides, endpoints, idempotencyKeyLifetime);
    }
  }
}
