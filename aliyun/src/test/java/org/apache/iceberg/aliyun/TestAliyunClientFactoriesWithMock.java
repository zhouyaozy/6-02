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
package org.apache.iceberg.aliyun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aliyun.oss.OSS;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import org.apache.iceberg.aliyun.oss.mock.AliyunOSSMockExtension;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.relocated.com.google.common.io.ByteStreams;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

public class TestAliyunClientFactoriesWithMock {

  private static final int MOCK_PORT = 9393;

  @RegisterExtension
  private static final AliyunOSSMockExtension MOCK_EXTENSION =
      AliyunOSSMockExtension.builder().build();

  private static String mockEndpoint;
  private final String bucketName = "test-bucket";

  @BeforeAll
  static void setupMockEndpoint() {
    mockEndpoint = String.format("http://localhost:%s", MOCK_PORT);
  }

  @BeforeEach
  void setUp() {
    MOCK_EXTENSION.setUpBucket(bucketName);
  }

  @AfterEach
  void tearDown() {
    MOCK_EXTENSION.tearDownBucket(bucketName);
  }

  @AfterAll
  static void cleanup() {
    MOCK_EXTENSION.stop();
  }

  @Test
  public void testDefaultFactoryIsSingleton() {
    assertThat(AliyunClientFactories.defaultFactory())
        .as("Default client should be singleton")
        .isSameAs(AliyunClientFactories.defaultFactory());
  }

  @Test
  public void testFromEmptyPropertiesLoadsDefault() {
    AliyunClientFactory factory = AliyunClientFactories.from(Maps.newHashMap());
    assertThat(factory)
        .as("Should load default when factory impl not configured")
        .isInstanceOf(AliyunClientFactories.DefaultAliyunClientFactory.class);
  }

  @Test
  public void testFromPropertiesWithAliyunConfig() {
    Map<String, String> properties =
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT, mockEndpoint,
            AliyunProperties.CLIENT_ACCESS_KEY_ID, "test-key",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET, "test-secret");

    AliyunClientFactory factory = AliyunClientFactories.from(properties);

    assertThat(factory).isInstanceOf(AliyunClientFactories.DefaultAliyunClientFactory.class);
    assertThat(factory.aliyunProperties().ossEndpoint()).isEqualTo(mockEndpoint);
    assertThat(factory.aliyunProperties().accessKeyId()).isEqualTo("test-key");
    assertThat(factory.aliyunProperties().accessKeySecret()).isEqualTo("test-secret");
  }

  @Test
  public void testFromPropertiesWithSecurityToken() {
    Map<String, String> properties =
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT, mockEndpoint,
            AliyunProperties.CLIENT_ACCESS_KEY_ID, "test-key",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET, "test-secret",
            AliyunProperties.CLIENT_SECURITY_TOKEN, "test-token");

    AliyunClientFactory factory = AliyunClientFactories.from(properties);

    assertThat(factory.aliyunProperties().securityToken()).isEqualTo("test-token");
  }

  @Test
  public void testNewOSSClientWithBasicCredentials() {
    Map<String, String> properties =
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT, mockEndpoint,
            AliyunProperties.CLIENT_ACCESS_KEY_ID, "foo",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET, "bar");

    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(properties);

    OSS ossClient = factory.newOSSClient();
    try {
      assertThat(ossClient).isNotNull();
      assertThat(ossClient.doesBucketExist(bucketName)).isTrue();
    } finally {
      ossClient.shutdown();
    }
  }

  @Test
  public void testNewOSSClientWithSecurityToken() {
    Map<String, String> properties =
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT, mockEndpoint,
            AliyunProperties.CLIENT_ACCESS_KEY_ID, "foo",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET, "bar",
            AliyunProperties.CLIENT_SECURITY_TOKEN, "test-token");

    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(properties);

    OSS ossClient = factory.newOSSClient();
    try {
      assertThat(ossClient).isNotNull();
      assertThat(ossClient.doesBucketExist(bucketName)).isTrue();
    } finally {
      ossClient.shutdown();
    }
  }

  @Test
  public void testNewOSSClientCanPutAndGetObject() {
    Map<String, String> properties =
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT, mockEndpoint,
            AliyunProperties.CLIENT_ACCESS_KEY_ID, "foo",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET, "bar");

    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(properties);

    OSS ossClient = factory.newOSSClient();
    try {
      String key = "test-object";
      byte[] content = "hello mock oss".getBytes();
      ossClient.putObject(bucketName, key, new ByteArrayInputStream(content));

      assertThat(ossClient.doesObjectExist(bucketName, key)).isTrue();

      try (InputStream is = ossClient.getObject(bucketName, key).getObjectContent()) {
        byte[] actual = ByteStreams.readFully(is);
        assertThat(actual).isEqualTo(content);
      }

      ossClient.deleteObject(bucketName, key);
      assertThat(ossClient.doesObjectExist(bucketName, key)).isFalse();
    } finally {
      ossClient.shutdown();
    }
  }

  @Test
  public void testNewOSSClientFailsWithoutInitialization() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();

    assertThatThrownBy(factory::newOSSClient)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Cannot create aliyun oss client before initializing");
  }

  @Test
  public void testAliyunPropertiesAfterInitialization() {
    Map<String, String> properties =
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT, mockEndpoint,
            AliyunProperties.CLIENT_ACCESS_KEY_ID, "my-key",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET, "my-secret",
            AliyunProperties.CLIENT_SECURITY_TOKEN, "my-token");

    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(properties);

    AliyunProperties aliyunProps = factory.aliyunProperties();
    assertThat(aliyunProps.ossEndpoint()).isEqualTo(mockEndpoint);
    assertThat(aliyunProps.accessKeyId()).isEqualTo("my-key");
    assertThat(aliyunProps.accessKeySecret()).isEqualTo("my-secret");
    assertThat(aliyunProps.securityToken()).isEqualTo("my-token");
  }

  @Test
  public void testAliyunPropertiesBeforeInitialization() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();

    assertThat(factory.aliyunProperties()).isNull();
  }

  @Test
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_OIDC_PROVIDER_ARN",
      value = "acs:ram::123456789:oidc-provider/test")
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_ROLE_ARN",
      value = "acs:ram::123456789:role/test-role")
  @SetEnvironmentVariable(key = "ALIBABA_CLOUD_OIDC_TOKEN_FILE", value = "/tmp/oidc-token")
  public void testIsRrsaEnvironmentAvailableWithEnvVars() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();

    assertThat(factory.isRrsaEnvironmentAvailable()).isTrue();
  }

  @Test
  public void testIsRrsaEnvironmentAvailableWithoutEnvVars() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();

    assertThat(factory.isRrsaEnvironmentAvailable()).isFalse();
  }

  @Test
  public void testCustomFactoryImplementation() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(AliyunProperties.CLIENT_FACTORY, TestCustomFactory.class.getName());
    properties.put(AliyunProperties.OSS_ENDPOINT, mockEndpoint);

    AliyunClientFactory factory = AliyunClientFactories.from(properties);

    assertThat(factory).isInstanceOf(TestCustomFactory.class);
    assertThat(factory.aliyunProperties().ossEndpoint()).isEqualTo(mockEndpoint);
  }

  @Test
  public void testCustomFactoryInvalidClass() {
    Map<String, String> properties =
        ImmutableMap.of(AliyunProperties.CLIENT_FACTORY, "java.lang.String");

    assertThatThrownBy(() -> AliyunClientFactories.from(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot initialize AliyunClientFactory");
  }

  @Test
  public void testCustomFactoryNonExistentClass() {
    Map<String, String> properties =
        ImmutableMap.of(AliyunProperties.CLIENT_FACTORY, "com.nonexistent.FakeFactory");

    assertThatThrownBy(() -> AliyunClientFactories.from(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot initialize AliyunClientFactory");
  }

  public static class TestCustomFactory implements AliyunClientFactory {

    private AliyunProperties aliyunProperties;

    public TestCustomFactory() {}

    @Override
    public OSS newOSSClient() {
      return null;
    }

    @Override
    public void initialize(Map<String, String> properties) {
      this.aliyunProperties = new AliyunProperties(properties);
    }

    @Override
    public AliyunProperties aliyunProperties() {
      return aliyunProperties;
    }
  }
}
