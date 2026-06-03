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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

public class AliyunOSSMock {

  static final String PROP_ROOT_DIR = "root-dir";
  static final String ROOT_DIR_DEFAULT = "/tmp";

  static final String PROP_HTTP_PORT = "server.port";
  static final int PORT_HTTP_PORT_DEFAULT = 9393;

  private final AliyunOSSMockLocalStore localStore;
  private final HttpServer httpServer;

  public static AliyunOSSMock start(Map<String, Object> properties) throws IOException {
    AliyunOSSMock mock =
        new AliyunOSSMock(
            properties.getOrDefault(PROP_ROOT_DIR, ROOT_DIR_DEFAULT).toString(),
            Integer.parseInt(
                properties.getOrDefault(PROP_HTTP_PORT, PORT_HTTP_PORT_DEFAULT).toString()));
    mock.start();
    return mock;
  }

  private AliyunOSSMock(String rootDir, int serverPort) throws IOException {
    localStore = new AliyunOSSMockLocalStore(rootDir);
    httpServer = HttpServer.create(new InetSocketAddress("localhost", serverPort), 0);
  }

  private void start() {
    httpServer.createContext("/", new AliyunHttpHandler(localStore));
    httpServer.start();
  }

  public void stop() {
    httpServer.stop(0);
  }
}
