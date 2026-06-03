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

import com.aliyun.credentials.models.CredentialModel;
import com.aliyun.credentials.provider.OIDCRoleArnCredentialProvider;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.BasicCredentials;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import java.util.Map;
import org.apache.iceberg.common.DynConstructors;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Strings;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AliyunClientFactories {

  private static final AliyunClientFactory ALIYUN_CLIENT_FACTORY_DEFAULT =
      new DefaultAliyunClientFactory();

  private AliyunClientFactories() {}

  public static AliyunClientFactory defaultFactory() {
    return ALIYUN_CLIENT_FACTORY_DEFAULT;
  }

  public static AliyunClientFactory from(Map<String, String> properties) {
    String factoryImpl =
        PropertyUtil.propertyAsString(
            properties,
            AliyunProperties.CLIENT_FACTORY,
            DefaultAliyunClientFactory.class.getName());
    return loadClientFactory(factoryImpl, properties);
  }

  private static AliyunClientFactory loadClientFactory(
      String impl, Map<String, String> properties) {
    DynConstructors.Ctor<AliyunClientFactory> ctor;
    try {
      ctor =
          DynConstructors.builder(AliyunClientFactory.class)
              .loader(AliyunClientFactories.class.getClassLoader())
              .hiddenImpl(impl)
              .buildChecked();
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException(
          String.format(
              "Cannot initialize AliyunClientFactory, missing no-arg constructor: %s", impl),
          e);
    }

    AliyunClientFactory factory;
    try {
      factory = ctor.newInstance();
    } catch (ClassCastException e) {
      throw new IllegalArgumentException(
          String.format(
              "Cannot initialize AliyunClientFactory, %s does not implement AliyunClientFactory.",
              impl),
          e);
    }

    factory.initialize(properties);
    return factory;
  }

  static class DefaultAliyunClientFactory implements AliyunClientFactory {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultAliyunClientFactory.class);

    private AliyunProperties aliyunProperties;

    DefaultAliyunClientFactory() {}

    @Override
    public OSS newOSSClient() {
      Preconditions.checkNotNull(
          aliyunProperties,
          "Cannot create Aliyun OSS client before initializing the AliyunClientFactory.");

      String endpoint = aliyunProperties.ossEndpoint();

      if (isRrsaEnvironmentAvailable()) {
        return createOSSClientWithRRSA(endpoint);
      } else if (Strings.isNullOrEmpty(aliyunProperties.securityToken())) {
        return new OSSClientBuilder()
            .build(
                endpoint,
                aliyunProperties.accessKeyId(),
                aliyunProperties.accessKeySecret());
      } else {
        return new OSSClientBuilder()
            .build(
                endpoint,
                aliyunProperties.accessKeyId(),
                aliyunProperties.accessKeySecret(),
                aliyunProperties.securityToken());
      }
    }

    private OSS createOSSClientWithRRSA(String endpoint) {
      LOG.info("Creating OSS client with RRSA credentials for endpoint: {}", endpoint);

      OIDCRoleArnCredentialProvider oidcProvider = OIDCRoleArnCredentialProvider.builder().build();

      CredentialsProvider credentialsProvider = createRRSACredentialsProvider(oidcProvider);

      try {
        return new OSSClientBuilder().build(endpoint, credentialsProvider);
      } catch (Exception e) {
        throw new RuntimeException("Failed to create OSS client with RRSA credentials", e);
      }
    }

    private CredentialsProvider createRRSACredentialsProvider(
        OIDCRoleArnCredentialProvider oidcProvider) {
      return new CredentialsProvider() {
        @Override
        public void setCredentials(Credentials credentials) {
        }

        @Override
        public Credentials getCredentials() {
          try {
            CredentialModel cred = oidcProvider.getCredentials();
            long expirationSeconds =
                cred.getExpiration() > 0
                    ? (cred.getExpiration() - System.currentTimeMillis()) / 1000
                    : 0;
            return new BasicCredentials(
                cred.getAccessKeyId(),
                cred.getAccessKeySecret(),
                cred.getSecurityToken(),
                expirationSeconds);
          } catch (Exception e) {
            throw new RuntimeException("Failed to get RRSA credentials", e);
          }
        }
      };
    }

    boolean isRrsaEnvironmentAvailable() {
      String oidcProviderArn = System.getenv("ALIBABA_CLOUD_OIDC_PROVIDER_ARN");
      String roleArn = System.getenv("ALIBABA_CLOUD_ROLE_ARN");
      String oidcTokenFile = System.getenv("ALIBABA_CLOUD_OIDC_TOKEN_FILE");
      return !Strings.isNullOrEmpty(oidcProviderArn)
          && !Strings.isNullOrEmpty(roleArn)
          && !Strings.isNullOrEmpty(oidcTokenFile);
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
