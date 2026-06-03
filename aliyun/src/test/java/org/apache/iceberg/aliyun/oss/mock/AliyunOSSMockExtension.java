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

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;
import org.apache.iceberg.aliyun.oss.AliyunOSSExtension;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Strings;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;

public class AliyunOSSMockExtension implements AliyunOSSExtension {

  private final AliyunOSSMockProperties mockProperties;

  private AliyunOSSMock ossMock;

  private AliyunOSSMockExtension(AliyunOSSMockProperties mockProperties) {
    this.mockProperties = mockProperties;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String keyPrefix() {
    return "mock-objects/";
  }

  @Override
  public void start() {
    try {
      ossMock = AliyunOSSMock.start(mockProperties);
    } catch (Exception e) {
      throw new RuntimeException("Can't start OSS Mock");
    }
  }

  @Override
  public void stop() {
    ossMock.stop();
  }

  @Override
  public OSS createOSSClient() {
    String endpoint =
        String.format("http://%s:%d", mockProperties.host(), mockProperties.httpPort());
    return new OSSClientBuilder().build(endpoint, "foo", "bar");
  }

  private File rootDir() {
    String rootDir = mockProperties.rootDir();
    Preconditions.checkNotNull(rootDir, "Root directory cannot be null");
    return new File(rootDir);
  }

  @Override
  public void setUpBucket(String bucket) {
    createOSSClient().createBucket(bucket);
  }

  @Override
  public void tearDownBucket(String bucket) {
    try {
      Files.walk(rootDir().toPath())
          .filter(p -> p.toFile().isFile())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  // delete this file quietly.
                }
              });

      createOSSClient().deleteBucket(bucket);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static class Builder {
    private final Map<String, String> props = Maps.newHashMap();

    public Builder rootDir(String rootDir) {
      props.put(AliyunOSSMockProperties.OSS_MOCK_ROOT_DIR, rootDir);
      return this;
    }

    public Builder httpPort(int port) {
      props.put(AliyunOSSMockProperties.OSS_MOCK_HTTP_PORT, String.valueOf(port));
      return this;
    }

    public Builder host(String host) {
      props.put(AliyunOSSMockProperties.OSS_MOCK_HOST, host);
      return this;
    }

    public AliyunOSSExtension build() {
      String rootDir = props.get(AliyunOSSMockProperties.OSS_MOCK_ROOT_DIR);
      if (Strings.isNullOrEmpty(rootDir)) {
        File dir =
            new File(
                System.getProperty("java.io.tmpdir"),
                "oss-mock-file-store-" + System.currentTimeMillis());
        rootDir = dir.getAbsolutePath();
        props.put(AliyunOSSMockProperties.OSS_MOCK_ROOT_DIR, rootDir);
      }
      File root = new File(rootDir);
      root.deleteOnExit();
      root.mkdir();
      return new AliyunOSSMockExtension(new AliyunOSSMockProperties(props));
    }
  }
}
