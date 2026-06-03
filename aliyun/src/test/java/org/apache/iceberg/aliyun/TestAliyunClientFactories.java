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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.credentials.provider.OIDCRoleArnCredentialProvider;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

public class TestAliyunClientFactories {

  @Test
  public void testLoadDefault() {
    assertThat(AliyunClientFactories.defaultFactory())
        .as("Default client should be singleton")
        .isEqualTo(AliyunClientFactories.defaultFactory());

    AliyunClientFactory defaultFactory = AliyunClientFactories.from(Maps.newHashMap());
    assertThat(defaultFactory)
        .as("Should load default when factory impl not configured")
        .isInstanceOf(AliyunClientFactories.DefaultAliyunClientFactory.class);

    assertThat(defaultFactory.aliyunProperties().accessKeyId())
        .as("Should have no Aliyun properties set")
        .isNull();

    assertThat(defaultFactory.aliyunProperties().securityToken())
        .as("Should have no security token")
        .isNull();

    AliyunClientFactory defaultFactoryWithConfig =
        AliyunClientFactories.from(
            ImmutableMap.of(
                AliyunProperties.CLIENT_ACCESS_KEY_ID,
                "key",
                AliyunProperties.CLIENT_SECURITY_TOKEN,
                "token"));
    assertThat(defaultFactoryWithConfig)
        .as("Should load default when factory impl not configured")
        .isInstanceOf(AliyunClientFactories.DefaultAliyunClientFactory.class);

    assertThat(defaultFactoryWithConfig.aliyunProperties().accessKeyId())
        .as("Should have access key set")
        .isEqualTo("key");

    assertThat(defaultFactoryWithConfig.aliyunProperties().securityToken())
        .as("Should have security token set")
        .isEqualTo("token");
  }

  @Test
  public void testLoadCustom() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(AliyunProperties.CLIENT_FACTORY, CustomFactory.class.getName());
    assertThat(AliyunClientFactories.from(properties))
        .as("Should load custom class")
        .isInstanceOf(CustomFactory.class);
  }

  @Test
  public void testLoadFactoryMissingNoArgConstructor() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(
        AliyunProperties.CLIENT_FACTORY, FactoryWithoutNoArgConstructor.class.getName());
    assertThatThrownBy(() -> AliyunClientFactories.from(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot initialize AliyunClientFactory, missing no-arg constructor");
  }

  @Test
  public void testLoadFactoryWrongType() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(AliyunProperties.CLIENT_FACTORY, NotAFactory.class.getName());
    assertThatThrownBy(() -> AliyunClientFactories.from(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not implement AliyunClientFactory");
  }

  @Test
  public void testNewOSSClientNotInitialized() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    assertThatThrownBy(factory::newOSSClient)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining(
            "Cannot create aliyun oss client before initializing the AliyunClientFactory");
  }

  @Test
  public void testNewOSSClientWithAccessKey() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT,
            "https://oss-cn-hangzhou.aliyuncs.com",
            AliyunProperties.CLIENT_ACCESS_KEY_ID,
            "test-access-key",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET,
            "test-secret-key"));

    try (MockedConstruction<OSSClientBuilder> mockedBuilder =
        mockConstruction(
            OSSClientBuilder.class,
            (mock, context) -> {
              when(mock.build(anyString(), anyString(), anyString()))
                  .thenReturn(mock(OSS.class));
            })) {

      OSS client = factory.newOSSClient();
      assertThat(client).isNotNull();

      OSSClientBuilder builderMock = mockedBuilder.constructed().get(0);
      assertThat(mockedBuilder.constructed()).hasSize(1);
      verify(builderMock)
          .build("https://oss-cn-hangzhou.aliyuncs.com", "test-access-key", "test-secret-key");
    }
  }

  @Test
  public void testNewOSSClientWithSecurityToken() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT,
            "https://oss-cn-hangzhou.aliyuncs.com",
            AliyunProperties.CLIENT_ACCESS_KEY_ID,
            "test-access-key",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET,
            "test-secret-key",
            AliyunProperties.CLIENT_SECURITY_TOKEN,
            "test-security-token"));

    try (MockedConstruction<OSSClientBuilder> mockedBuilder =
        mockConstruction(
            OSSClientBuilder.class,
            (mock, context) -> {
              when(mock.build(anyString(), anyString(), anyString(), anyString()))
                  .thenReturn(mock(OSS.class));
            })) {

      OSS client = factory.newOSSClient();
      assertThat(client).isNotNull();

      OSSClientBuilder builderMock = mockedBuilder.constructed().get(0);
      assertThat(mockedBuilder.constructed()).hasSize(1);
      verify(builderMock)
          .build(
              "https://oss-cn-hangzhou.aliyuncs.com",
              "test-access-key",
              "test-secret-key",
              "test-security-token");
    }
  }

  @Test
  public void testNewOSSClientWithEmptySecurityTokenFallsBackToAccessKey() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT,
            "https://oss-cn-hangzhou.aliyuncs.com",
            AliyunProperties.CLIENT_ACCESS_KEY_ID,
            "test-access-key",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET,
            "test-secret-key",
            AliyunProperties.CLIENT_SECURITY_TOKEN,
            ""));

    try (MockedConstruction<OSSClientBuilder> mockedBuilder =
        mockConstruction(
            OSSClientBuilder.class,
            (mock, context) -> {
              when(mock.build(anyString(), anyString(), anyString()))
                  .thenReturn(mock(OSS.class));
            })) {

      OSS client = factory.newOSSClient();
      assertThat(client).isNotNull();

      OSSClientBuilder builderMock = mockedBuilder.constructed().get(0);
      assertThat(mockedBuilder.constructed()).hasSize(1);
      verify(builderMock)
          .build("https://oss-cn-hangzhou.aliyuncs.com", "test-access-key", "test-secret-key");
    }
  }

  @Test
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_OIDC_PROVIDER_ARN",
      value = "acs:ram::123456789:oidc-provider/ack-rrsa-test")
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_ROLE_ARN",
      value = "acs:ram::123456789:role/test-rrsa-role")
  @SetEnvironmentVariable(key = "ALIBABA_CLOUD_OIDC_TOKEN_FILE", value = "/tmp/oidc-token")
  public void testNewOSSClientWithRRSA() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT, "https://oss-cn-hangzhou.aliyuncs.com"));

    assertThat(factory.isRrsaEnvironmentAvailable()).isTrue();

    CredentialModel mockCredential = mock(CredentialModel.class);
    when(mockCredential.getAccessKeyId()).thenReturn("rrsa-access-key");
    when(mockCredential.getAccessKeySecret()).thenReturn("rrsa-secret-key");
    when(mockCredential.getSecurityToken()).thenReturn("rrsa-token");
    when(mockCredential.getExpiration()).thenReturn(0L);

    try (MockedConstruction<OIDCRoleArnCredentialProvider> mockedOidc =
            mockConstruction(
                OIDCRoleArnCredentialProvider.class,
                (mock, ctx) -> when(mock.getCredentials()).thenReturn(mockCredential));
        MockedConstruction<OSSClientBuilder> mockedBuilder =
            mockConstruction(
                OSSClientBuilder.class,
                (mock, ctx) ->
                    when(mock.build(anyString(), any(CredentialsProvider.class)))
                        .thenReturn(mock(OSS.class)))) {

      OSS client = factory.newOSSClient();
      assertThat(client).isNotNull();

      assertThat(mockedBuilder.constructed()).hasSize(1);
      assertThat(mockedOidc.constructed()).hasSize(1);

      OSSClientBuilder builderMock = mockedBuilder.constructed().get(0);
      verify(builderMock)
          .build("https://oss-cn-hangzhou.aliyuncs.com", any(CredentialsProvider.class));
    }
  }

  @Test
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_OIDC_PROVIDER_ARN",
      value = "acs:ram::123456789:oidc-provider/ack-rrsa-test")
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_ROLE_ARN",
      value = "acs:ram::123456789:role/test-rrsa-role")
  @SetEnvironmentVariable(key = "ALIBABA_CLOUD_OIDC_TOKEN_FILE", value = "/tmp/oidc-token")
  public void testNewOSSClientWithRRSAThrowsOnOIDCFailure() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT, "https://oss-cn-hangzhou.aliyuncs.com"));

    assertThat(factory.isRrsaEnvironmentAvailable()).isTrue();

    try (MockedConstruction<OIDCRoleArnCredentialProvider> mockedOidc =
        mockConstruction(
            OIDCRoleArnCredentialProvider.class,
            (mock, ctx) -> when(mock.getCredentials()).thenThrow(new RuntimeException("OIDC error")))) {

      assertThatThrownBy(factory::newOSSClient)
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to get RRSA credentials");
    }
  }

  @Test
  public void testIsRrsaEnvironmentAvailableWithoutEnvVars() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    assertThat(factory.isRrsaEnvironmentAvailable())
        .as("RRSA should not be available without environment variables")
        .isFalse();
  }

  @Test
  public void testIsRrsaEnvironmentAvailablePartialEnvVars() {
    try (MockedStatic<System> systemMock = mockStatic(System.class)) {
      systemMock.when(() -> System.getenv("ALIBABA_CLOUD_OIDC_PROVIDER_ARN"))
          .thenReturn("acs:ram::123456789:oidc-provider/ack-rrsa-test");
      systemMock.when(() -> System.getenv("ALIBABA_CLOUD_ROLE_ARN")).thenReturn(null);
      systemMock.when(() -> System.getenv("ALIBABA_CLOUD_OIDC_TOKEN_FILE")).thenReturn(null);
      systemMock.when(() -> System.getenv(anyString())).thenCallRealMethod();

      AliyunClientFactories.DefaultAliyunClientFactory factory =
          new AliyunClientFactories.DefaultAliyunClientFactory();
      assertThat(factory.isRrsaEnvironmentAvailable())
          .as("RRSA should not be available with partial env vars")
          .isFalse();
    }
  }

  public static class CustomFactory implements AliyunClientFactory {

    AliyunProperties aliyunProperties;

    public CustomFactory() {}

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

  static class FactoryWithoutNoArgConstructor implements AliyunClientFactory {

    public FactoryWithoutNoArgConstructor(String ignored) {}

    @Override
    public OSS newOSSClient() {
      return null;
    }

    @Override
    public void initialize(Map<String, String> properties) {}

    @Override
    public AliyunProperties aliyunProperties() {
      return null;
    }
  }

  static class NotAFactory {
    public NotAFactory() {}
  }
}