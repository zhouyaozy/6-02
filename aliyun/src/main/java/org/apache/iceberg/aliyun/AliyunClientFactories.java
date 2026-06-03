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

  /**
   * Load an implemented {@link AliyunClientFactory} based on the class name, and initialize it.
   *
   * @param impl the class name.
   * @param properties to initialize the factory.
   * @return an initialized {@link AliyunClientFactory}.
   */
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
    private static final String RRSA_OIDC_PROVIDER_ARN = "ALIBABA_CLOUD_OIDC_PROVIDER_ARN";
    private static final String RRSA_ROLE_ARN = "ALIBABA_CLOUD_ROLE_ARN";
    private static final String RRSA_OIDC_TOKEN_FILE = "ALIBABA_CLOUD_OIDC_TOKEN_FILE";

    private AliyunProperties aliyunProperties;
    private transient volatile OIDCRoleArnCredentialProvider rrsaCredentialProvider;

    DefaultAliyunClientFactory() {}

    /**
     * Check if RRSA environment variables are present. RRSA requires
     * ALIBABA_CLOUD_OIDC_PROVIDER_ARN, ALIBABA_CLOUD_ROLE_ARN and ALIBABA_CLOUD_OIDC_TOKEN_FILE to
     * be set. RRSA stands for RAM Roles for Service Accounts. It works by letting pods assume
     * specific RAM (Resource Access Management) roles when they need to call cloud APIs — no
     * hard-coded credentials are needed, which reduces risk of credential leaks. Here is the
     * document for RRSA:
     * https://www.alibabacloud.com/help/en/ack/ack-managed-and-ack-dedicated/user-guide/use-rrsa-to-authorize-pods-to-access-different-cloud-services
     */
    boolean isRrsaEnvironmentAvailable() {
      return !Strings.isNullOrEmpty(System.getenv(RRSA_OIDC_PROVIDER_ARN))
          && !Strings.isNullOrEmpty(System.getenv(RRSA_ROLE_ARN))
          && !Strings.isNullOrEmpty(System.getenv(RRSA_OIDC_TOKEN_FILE));
    }

    @Override
    public OSS newOSSClient() {
      Preconditions.checkNotNull(
          aliyunProperties,
          "Cannot create aliyun oss client before initializing the AliyunClientFactory.");

      String endpoint = aliyunProperties.ossEndpoint();
      if (isRrsaEnvironmentAvailable()) {
        LOG.info("Creating OSS client with RRSA credentials for endpoint: {}", endpoint);
        return new OSSClientBuilder().build(endpoint, new RrsaCredentialsProvider(this));
      }

      if (Strings.isNullOrEmpty(aliyunProperties.securityToken())) {
        return new OSSClientBuilder()
            .build(endpoint, aliyunProperties.accessKeyId(), aliyunProperties.accessKeySecret());
      }

      return new OSSClientBuilder()
          .build(
              endpoint,
              aliyunProperties.accessKeyId(),
              aliyunProperties.accessKeySecret(),
              aliyunProperties.securityToken());
    }

    Credentials newRrsaCredentials() {
      try {
        CredentialModel credentialModel = rrsaCredentialProvider().getCredentials();
        return toCredentials(credentialModel);
      } catch (Exception e) {
        throw new IllegalStateException("Failed to get RRSA credentials", e);
      }
    }

    OIDCRoleArnCredentialProvider rrsaCredentialProvider() {
      if (rrsaCredentialProvider == null) {
        synchronized (this) {
          if (rrsaCredentialProvider == null) {
            rrsaCredentialProvider = OIDCRoleArnCredentialProvider.builder().build();
          }
        }
      }

      return rrsaCredentialProvider;
    }

    private Credentials toCredentials(CredentialModel credentialModel) {
      long expirationSeconds = 0;
      if (credentialModel.getExpiration() > 0) {
        expirationSeconds =
            Math.max(0L, credentialModel.getExpiration() - System.currentTimeMillis()) / 1000;
      }

      return new BasicCredentials(
          credentialModel.getAccessKeyId(),
          credentialModel.getAccessKeySecret(),
          credentialModel.getSecurityToken(),
          expirationSeconds);
    }

    @Override
    public void initialize(Map<String, String> properties) {
      this.aliyunProperties = new AliyunProperties(properties);
      this.rrsaCredentialProvider = null;
    }

    @Override
    public AliyunProperties aliyunProperties() {
      return aliyunProperties;
    }
  }

  private static class RrsaCredentialsProvider implements CredentialsProvider {
    private final DefaultAliyunClientFactory clientFactory;

    private RrsaCredentialsProvider(DefaultAliyunClientFactory clientFactory) {
      this.clientFactory = clientFactory;
    }

    @Override
    public void setCredentials(Credentials credentials) {}

    @Override
    public Credentials getCredentials() {
      return clientFactory.newRrsaCredentials();
    }
  }
}
