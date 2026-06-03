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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.junit.jupiter.api.Test;

public class TestConfigManager {

  @Test
  public void testLoadFromProperties() {
    Map<String, Object> properties = Maps.newHashMap();
    properties.put(ConfigManager.PROP_DEFAULTS, ImmutableMap.of("warehouse", "s3://bucket/warehouse"));
    properties.put(ConfigManager.PROP_DEFAULT_PREFIX + "clients", 5);
    properties.put(ConfigManager.PROP_OVERRIDES, ImmutableMap.of("region", "cn-hangzhou"));
    properties.put(ConfigManager.PROP_OVERRIDE_PREFIX + "clients", 10);

    ConfigManager manager = ConfigManager.from(properties);

    assertThat(manager.defaults())
        .isEqualTo(ImmutableMap.of("warehouse", "s3://bucket/warehouse", "clients", "5"));
    assertThat(manager.overrides())
        .isEqualTo(ImmutableMap.of("region", "cn-hangzhou", "clients", "10"));
  }

  @Test
  public void testMergeUsesDefaultsClientAndOverridesPrecedence() {
    Map<String, String> defaults = ImmutableMap.of("a", "from_defaults", "b", "from_defaults");
    Map<String, String> overrides = Maps.newHashMap();
    overrides.put("a", null);
    overrides.put("b", "from_overrides");

    ConfigManager manager = ConfigManager.from(defaults, overrides);
    Map<String, String> clientProperties = ImmutableMap.of("a", "from_client", "c", "from_client");

    assertThat(manager.merge(clientProperties))
        .isEqualTo(ImmutableMap.of("b", "from_overrides", "c", "from_client"));
  }

  @Test
  public void testRejectsInvalidPropertyPayload() {
    assertThatThrownBy(() -> ConfigManager.from(ImmutableMap.of(ConfigManager.PROP_DEFAULTS, "invalid")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid defaults properties: expected a map but found java.lang.String");
  }
}
