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
package org.apache.iceberg.aliyun.oss.mock;

import java.io.Serializable;
import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.util.PropertyUtil;

public class AliyunOSSMockProperties implements Serializable {

  public static final String OSS_MOCK_ROOT_DIR = "oss-mock.root-dir";
  public static final String OSS_MOCK_ROOT_DIR_DEFAULT = "/tmp";

  public static final String OSS_MOCK_HTTP_PORT = "oss-mock.http-port";
  public static final int OSS_MOCK_HTTP_PORT_DEFAULT = 9393;

  public static final String OSS_MOCK_HOST = "oss-mock.host";
  public static final String OSS_MOCK_HOST_DEFAULT = "localhost";

  private final String rootDir;
  private final int httpPort;
  private final String host;

  public AliyunOSSMockProperties() {
    this(ImmutableMap.of());
  }

  public AliyunOSSMockProperties(Map<String, String> properties) {
    this.rootDir =
        PropertyUtil.propertyAsString(
            properties, OSS_MOCK_ROOT_DIR, OSS_MOCK_ROOT_DIR_DEFAULT);
    this.httpPort =
        PropertyUtil.propertyAsInt(
            properties, OSS_MOCK_HTTP_PORT, OSS_MOCK_HTTP_PORT_DEFAULT);
    this.host =
        PropertyUtil.propertyAsString(
            properties, OSS_MOCK_HOST, OSS_MOCK_HOST_DEFAULT);
  }

  public String rootDir() {
    return rootDir;
  }

  public int httpPort() {
    return httpPort;
  }

  public String host() {
    return host;
  }
}
