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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.credentials.provider.OIDCRoleArnCredentialProvider;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

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
  public void testIsRrsaEnvironmentAvailableWithoutEnvVars() {
    // Verify that isRrsaEnvironmentAvailable returns false when env vars are not set
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();

    // Assuming RRSA env vars are not set in test environment
    assertThat(factory.isRrsaEnvironmentAvailable())
        .as("RRSA should not be available without environment variables")
        .isFalse();
  }

  @Test
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_OIDC_PROVIDER_ARN",
      value = "acs:ram::123456789:oidc-provider/ack-rrsa-test")
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_ROLE_ARN",
      value = "acs:ram::123456789:role/test-rrsa-role")
  @SetEnvironmentVariable(key = "ALIBABA_CLOUD_OIDC_TOKEN_FILE", value = "/tmp/oidc-token")
  public void testRRSAClientWithMock() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(AliyunProperties.OSS_ENDPOINT, "https://oss-cn-hangzhou.aliyuncs.com");

    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(properties);

    CredentialModel fakeCred = new CredentialModel();
    fakeCred.setAccessKeyId("fake-id");
    fakeCred.setAccessKeySecret("fake-secret");
    fakeCred.setSecurityToken("fake-token");
    fakeCred.setExpiration(System.currentTimeMillis() + 3600000L);

    OIDCRoleArnCredentialProvider fakeProvider = mock(OIDCRoleArnCredentialProvider.class);
    when(fakeProvider.getCredentials()).thenReturn(fakeCred);

    OIDCRoleArnCredentialProvider.Builder mockBuilder =
        mock(OIDCRoleArnCredentialProvider.Builder.class);
    when(mockBuilder.build()).thenReturn(fakeProvider);

    try (MockedStatic<OIDCRoleArnCredentialProvider> mockedStatic =
            mockStatic(OIDCRoleArnCredentialProvider.class);
        MockedConstruction<OSSClientBuilder> mockedBuilder =
            mockConstruction(
                OSSClientBuilder.class,
                (mockBuilderInstance, context) -> {
                  OSS fakeOss = mock(OSS.class);
                  when(mockBuilderInstance.build(anyString(), any(CredentialsProvider.class)))
                      .thenAnswer(
                          invocation -> {
                            CredentialsProvider provider = invocation.getArgument(1);
                            Credentials credentials = provider.getCredentials();
                            assertThat(credentials.getAccessKeyId()).isEqualTo("fake-id");
                            assertThat(credentials.getSecretAccessKey()).isEqualTo("fake-secret");
                            assertThat(credentials.getSecurityToken()).isEqualTo("fake-token");
                            return fakeOss;
                          });
                })) {

      mockedStatic.when(OIDCRoleArnCredentialProvider::builder).thenReturn(mockBuilder);

      OSS client = factory.newOSSClient();
      assertThat(client).isNotNull();
    }
  }

  @Test
  public void testBasicCredentialsWithMock() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(AliyunProperties.OSS_ENDPOINT, "https://oss-cn-hangzhou.aliyuncs.com");
    properties.put(AliyunProperties.CLIENT_ACCESS_KEY_ID, "test-id");
    properties.put(AliyunProperties.CLIENT_ACCESS_KEY_SECRET, "test-secret");

    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(properties);

    try (MockedConstruction<OSSClientBuilder> mockedBuilder =
        mockConstruction(
            OSSClientBuilder.class,
            (mockBuilderInstance, context) -> {
              OSS fakeOss = mock(OSS.class);
              when(mockBuilderInstance.build(anyString(), anyString(), anyString()))
                  .thenAnswer(
                      invocation -> {
                        assertThat(invocation.<String>getArgument(0))
                            .isEqualTo("https://oss-cn-hangzhou.aliyuncs.com");
                        assertThat(invocation.<String>getArgument(1)).isEqualTo("test-id");
                        assertThat(invocation.<String>getArgument(2)).isEqualTo("test-secret");
                        return fakeOss;
                      });
            })) {

      OSS client = factory.newOSSClient();
      assertThat(client).isNotNull();
    }
  }

  @Test
  public void testSecurityTokenCredentialsWithMock() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(AliyunProperties.OSS_ENDPOINT, "https://oss-cn-hangzhou.aliyuncs.com");
    properties.put(AliyunProperties.CLIENT_ACCESS_KEY_ID, "test-id");
    properties.put(AliyunProperties.CLIENT_ACCESS_KEY_SECRET, "test-secret");
    properties.put(AliyunProperties.CLIENT_SECURITY_TOKEN, "test-token");

    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    factory.initialize(properties);

    try (MockedConstruction<OSSClientBuilder> mockedBuilder =
        mockConstruction(
            OSSClientBuilder.class,
            (mockBuilderInstance, context) -> {
              OSS fakeOss = mock(OSS.class);
              when(mockBuilderInstance.build(anyString(), anyString(), anyString(), anyString()))
                  .thenAnswer(
                      invocation -> {
                        assertThat(invocation.<String>getArgument(0))
                            .isEqualTo("https://oss-cn-hangzhou.aliyuncs.com");
                        assertThat(invocation.<String>getArgument(1)).isEqualTo("test-id");
                        assertThat(invocation.<String>getArgument(2)).isEqualTo("test-secret");
                        assertThat(invocation.<String>getArgument(3)).isEqualTo("test-token");
                        return fakeOss;
                      });
            })) {

      OSS client = factory.newOSSClient();
      assertThat(client).isNotNull();
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
}
