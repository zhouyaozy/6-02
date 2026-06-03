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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TestAppProperties {

  private static final String PROP_ROOT_DIR = "root-dir";
  private static final String ROOT_DIR_DEFAULT = "/tmp";

  private static final String PROP_HTTP_PORT = "server.port";
  private static final int PORT_HTTP_PORT_DEFAULT = 9393;

  @Test
  public void testBuildWithDefaults() {
    AppProperties props = AppProperties.builder().build();

    assertThat(props.get(PROP_ROOT_DIR, ROOT_DIR_DEFAULT)).isEqualTo("/tmp");
    assertThat(props.getInt(PROP_HTTP_PORT, PORT_HTTP_PORT_DEFAULT)).isEqualTo(9393);
  }

  @Test
  public void testBuildWithCustomValues() {
    AppProperties props =
        AppProperties.builder()
            .with(PROP_ROOT_DIR, "/custom/dir")
            .with(PROP_HTTP_PORT, 8080)
            .build();

    assertThat(props.get(PROP_ROOT_DIR, ROOT_DIR_DEFAULT)).isEqualTo("/custom/dir");
    assertThat(props.getInt(PROP_HTTP_PORT, PORT_HTTP_PORT_DEFAULT)).isEqualTo(8080);
  }

  @Test
  public void testGetOrDefault() {
    AppProperties props = AppProperties.builder().build();

    assertThat(props.get("nonexistent", "default")).isEqualTo("default");
    assertThat(props.getInt("nonexistent", 42)).isEqualTo(42);
    assertThat(props.getLong("nonexistent", 100L)).isEqualTo(100L);
    assertThat(props.getBoolean("nonexistent", true)).isTrue();
    assertThat(props.getDouble("nonexistent", 3.14)).isEqualTo(3.14);
  }

  @Test
  public void testGetWithNullDefault() {
    AppProperties props = AppProperties.builder().build();

    assertThat(props.get("nonexistent")).isNull();
  }

  @Test
  public void testWithAll() {
    Map<String, Object> source = new HashMap<>();
    source.put(PROP_ROOT_DIR, "/data");
    source.put(PROP_HTTP_PORT, 7070);

    AppProperties props = AppProperties.builder().withAll(source).build();

    assertThat(props.get(PROP_ROOT_DIR, ROOT_DIR_DEFAULT)).isEqualTo("/data");
    assertThat(props.getInt(PROP_HTTP_PORT, PORT_HTTP_PORT_DEFAULT)).isEqualTo(7070);
  }

  @Test
  public void testAsMap() {
    AppProperties props =
        AppProperties.builder().with(PROP_ROOT_DIR, "/data").build();

    Map<String, Object> map = props.asMap();
    assertThat(map).containsEntry(PROP_ROOT_DIR, "/data");
  }

  @Test
  public void testPropertyEntryUsage() {
    PropertyEntry<String> rootDir = PropertyEntry.string("root-dir", "/tmp");
    PropertyEntry<Integer> port = PropertyEntry.integer("server.port", 9393);

    AppProperties props =
        AppProperties.builder()
            .with(rootDir.key(), "/custom")
            .with(port.key(), 8080)
            .build();

    assertThat(props.get(rootDir.key(), rootDir.defaultValue())).isEqualTo("/custom");
    assertThat(props.getInt(port.key(), port.defaultValue())).isEqualTo(8080);
  }

  @Test
  public void testPropertyEntryDefaults() {
    PropertyEntry<String> rootDir = PropertyEntry.string("root-dir", "/tmp");
    PropertyEntry<Integer> port = PropertyEntry.integer("server.port", 9393);
    PropertyEntry<Long> timeout = PropertyEntry.longEntry("timeout", 30000L);
    PropertyEntry<Boolean> enabled = PropertyEntry.bool("enabled", true);
    PropertyEntry<Double> ratio = PropertyEntry.doubleEntry("ratio", 0.75);

    AppProperties props = AppProperties.builder().build();

    assertThat(props.get(rootDir.key(), rootDir.defaultValue())).isEqualTo("/tmp");
    assertThat(props.getInt(port.key(), port.defaultValue())).isEqualTo(9393);
    assertThat(props.getLong(timeout.key(), timeout.defaultValue())).isEqualTo(30000L);
    assertThat(props.getBoolean(enabled.key(), enabled.defaultValue())).isTrue();
    assertThat(props.getDouble(ratio.key(), ratio.defaultValue())).isEqualTo(0.75);
  }
}