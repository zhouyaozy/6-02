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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

class TestAliyunClientFactories {
  private static final String ENDPOINT = "https://oss-cn-hangzhou.aliyuncs.com";
  private static final String ACCESS_KEY_ID = "access-key-id";
  private static final String ACCESS_KEY_SECRET = "access-key-secret";
  private static final String SECURITY_TOKEN = "security-token";
  private static final String OIDC_TOKEN_FILE = "/tmp/iceberg-aliyun-rrsa-token";

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
  public void testLoadClientFactoryWithoutNoArgConstructor() {
    assertThatThrownBy(
            () ->
                AliyunClientFactories.from(
                    ImmutableMap.of(
                        AliyunProperties.CLIENT_FACTORY,
                        NoArgConstructorMissingFactory.class.getName())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing no-arg constructor")
        .hasMessageContaining(NoArgConstructorMissingFactory.class.getName());
  }

  @Test
  public void testLoadClientFactoryRejectsNonFactoryImplementation() {
    assertThatThrownBy(
            () ->
                AliyunClientFactories.from(
                    ImmutableMap.of(AliyunProperties.CLIENT_FACTORY, String.class.getName())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not implement AliyunClientFactory")
        .hasMessageContaining(String.class.getName());
  }

  @Test
  public void testNewOSSClientRequiresInitialization() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();

    assertThatThrownBy(factory::newOSSClient)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("before initializing the AliyunClientFactory");
  }

  @Test
  public void testNewOSSClientUsesAccessKeyCredentials() {
    OSS ossClient = mock(OSS.class);
    AccessKeyOnlyFactory factory = new AccessKeyOnlyFactory();
    factory.initialize(accessKeyProperties());

    try (MockedConstruction<OSSClientBuilder> mockedConstruction =
        Mockito.mockConstruction(
            OSSClientBuilder.class,
            (mockBuilder, context) ->
                when(mockBuilder.build(ENDPOINT, ACCESS_KEY_ID, ACCESS_KEY_SECRET))
                    .thenReturn(ossClient))) {
      OSS client = factory.newOSSClient();

      assertThat(client).isSameAs(ossClient);
      assertThat(mockedConstruction.constructed()).hasSize(1);
      verify(mockedConstruction.constructed().get(0))
          .build(ENDPOINT, ACCESS_KEY_ID, ACCESS_KEY_SECRET);
    }
  }

  @Test
  public void testNewOSSClientUsesSecurityTokenCredentials() {
    OSS ossClient = mock(OSS.class);
    AccessKeyOnlyFactory factory = new AccessKeyOnlyFactory();
    factory.initialize(accessKeyWithTokenProperties());

    try (MockedConstruction<OSSClientBuilder> mockedConstruction =
        Mockito.mockConstruction(
            OSSClientBuilder.class,
            (mockBuilder, context) ->
                when(mockBuilder.build(ENDPOINT, ACCESS_KEY_ID, ACCESS_KEY_SECRET, SECURITY_TOKEN))
                    .thenReturn(ossClient))) {
      OSS client = factory.newOSSClient();

      assertThat(client).isSameAs(ossClient);
      assertThat(mockedConstruction.constructed()).hasSize(1);
      verify(mockedConstruction.constructed().get(0))
          .build(ENDPOINT, ACCESS_KEY_ID, ACCESS_KEY_SECRET, SECURITY_TOKEN);
    }
  }

  @Test
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_OIDC_PROVIDER_ARN",
      value = "acs:ram::123456789:oidc-provider/ack-rrsa-test")
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_ROLE_ARN",
      value = "acs:ram::123456789:role/test-rrsa-role")
  @SetEnvironmentVariable(key = "ALIBABA_CLOUD_OIDC_TOKEN_FILE", value = OIDC_TOKEN_FILE)
  public void testNewOSSClientUsesRrsaCredentialsProvider() throws IOException {
    Files.writeString(Path.of(OIDC_TOKEN_FILE), "token");

    OSS ossClient = mock(OSS.class);
    RrsaFactory factory = new RrsaFactory();
    factory.initialize(ImmutableMap.of(AliyunProperties.OSS_ENDPOINT, ENDPOINT));

    try (MockedConstruction<OSSClientBuilder> mockedConstruction =
        Mockito.mockConstruction(
            OSSClientBuilder.class,
            (mockBuilder, context) ->
                when(mockBuilder.build(eq(ENDPOINT), any(CredentialsProvider.class)))
                    .thenReturn(ossClient))) {
      OSS client = factory.newOSSClient();

      assertThat(client).isSameAs(ossClient);
      assertThat(mockedConstruction.constructed()).hasSize(1);
      verify(mockedConstruction.constructed().get(0))
          .build(eq(ENDPOINT), any(CredentialsProvider.class));
    } finally {
      Files.deleteIfExists(Path.of(OIDC_TOKEN_FILE));
    }
  }

  private static Map<String, String> accessKeyProperties() {
    return ImmutableMap.of(
        AliyunProperties.OSS_ENDPOINT,
        ENDPOINT,
        AliyunProperties.CLIENT_ACCESS_KEY_ID,
        ACCESS_KEY_ID,
        AliyunProperties.CLIENT_ACCESS_KEY_SECRET,
        ACCESS_KEY_SECRET);
  }

  private static Map<String, String> accessKeyWithTokenProperties() {
    return ImmutableMap.of(
        AliyunProperties.OSS_ENDPOINT,
        ENDPOINT,
        AliyunProperties.CLIENT_ACCESS_KEY_ID,
        ACCESS_KEY_ID,
        AliyunProperties.CLIENT_ACCESS_KEY_SECRET,
        ACCESS_KEY_SECRET,
        AliyunProperties.CLIENT_SECURITY_TOKEN,
        SECURITY_TOKEN);
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

  public static class NoArgConstructorMissingFactory implements AliyunClientFactory {
    public NoArgConstructorMissingFactory(String ignored) {}

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

  static class AccessKeyOnlyFactory extends AliyunClientFactories.DefaultAliyunClientFactory {
    @Override
    boolean isRrsaEnvironmentAvailable() {
      return false;
    }
  }

  static class RrsaFactory extends AliyunClientFactories.DefaultAliyunClientFactory {
    @Override
    boolean isRrsaEnvironmentAvailable() {
      return true;
    }
  }
}
