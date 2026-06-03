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

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.common.auth.BasicCredentials;
import com.aliyun.oss.common.auth.Credentials;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.junit.jupiter.api.Test;
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
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_OIDC_PROVIDER_ARN",
      value = "acs:ram::123456789:oidc-provider/ack-rrsa-test")
  @SetEnvironmentVariable(
      key = "ALIBABA_CLOUD_ROLE_ARN",
      value = "acs:ram::123456789:role/test-rrsa-role")
  @SetEnvironmentVariable(key = "ALIBABA_CLOUD_OIDC_TOKEN_FILE", value = "/tmp/oidc-token")
  public void testRRSAEnvironmentDetection() {
    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new AliyunClientFactories.DefaultAliyunClientFactory();
    assertThat(factory.isRrsaEnvironmentAvailable()).isTrue();
  }

  @Test
  public void testCreateClientWithConfiguredSecurityToken() throws URISyntaxException {
    Map<String, String> properties =
        ImmutableMap.of(
            AliyunProperties.OSS_ENDPOINT,
            "oss-cn-hangzhou.aliyuncs.com",
            AliyunProperties.CLIENT_ACCESS_KEY_ID,
            "access-key-id",
            AliyunProperties.CLIENT_ACCESS_KEY_SECRET,
            "access-key-secret",
            AliyunProperties.CLIENT_SECURITY_TOKEN,
            "security-token");

    AliyunClientFactories.DefaultAliyunClientFactory factory = new NonRrsaAliyunClientFactory();
    factory.initialize(properties);

    OSS client = factory.newOSSClient();
    assertThat(client).isInstanceOf(OSSClient.class);

    OSSClient ossClient = (OSSClient) client;
    assertThat(ossClient.getEndpoint()).isEqualTo(new URI("http://oss-cn-hangzhou.aliyuncs.com"));
    assertCredentials(ossClient, "access-key-id", "access-key-secret", "security-token");

    client.shutdown();
  }

  @Test
  public void testCreateClientWithRrsaCredentials() throws URISyntaxException {
    Map<String, String> properties =
        ImmutableMap.of(AliyunProperties.OSS_ENDPOINT, "oss-cn-hangzhou.aliyuncs.com");

    AliyunClientFactories.DefaultAliyunClientFactory factory =
        new StubRrsaAliyunClientFactory(
            new BasicCredentials("rrsa-access-key-id", "rrsa-access-key-secret", "rrsa-token", 1));
    factory.initialize(properties);

    OSS client = factory.newOSSClient();
    assertThat(client).isInstanceOf(OSSClient.class);

    OSSClient ossClient = (OSSClient) client;
    assertThat(ossClient.getEndpoint()).isEqualTo(new URI("http://oss-cn-hangzhou.aliyuncs.com"));
    assertCredentials(
        ossClient, "rrsa-access-key-id", "rrsa-access-key-secret", "rrsa-token");

    client.shutdown();
  }

  private void assertCredentials(
      OSSClient client, String accessKeyId, String accessKeySecret, String securityToken) {
    assertThat(client.getCredentialsProvider().getCredentials().getAccessKeyId())
        .isEqualTo(accessKeyId);
    assertThat(client.getCredentialsProvider().getCredentials().getSecretAccessKey())
        .isEqualTo(accessKeySecret);
    assertThat(client.getCredentialsProvider().getCredentials().getSecurityToken())
        .isEqualTo(securityToken);
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

  private static class NonRrsaAliyunClientFactory
      extends AliyunClientFactories.DefaultAliyunClientFactory {

    @Override
    boolean isRrsaEnvironmentAvailable() {
      return false;
    }
  }

  private static class StubRrsaAliyunClientFactory
      extends AliyunClientFactories.DefaultAliyunClientFactory {
    private final Credentials rrsaCredentials;

    private StubRrsaAliyunClientFactory(Credentials rrsaCredentials) {
      this.rrsaCredentials = rrsaCredentials;
    }

    @Override
    boolean isRrsaEnvironmentAvailable() {
      return true;
    }

    @Override
    Credentials newRrsaCredentials() {
      return rrsaCredentials;
    }
  }
}
