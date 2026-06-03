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

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.CredentialModel;
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
      ctor = DynConstructors.builder(AliyunClientFactory.class).hiddenImpl(impl).buildChecked();
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
          "Cannot create aliyun oss client before initializing the AliyunClientFactory.");

      String endpoint = aliyunProperties.ossEndpoint();

      if (Strings.isNullOrEmpty(aliyunProperties.accessKeyId())) {
        try {
          LOG.info(
              "Access key ID is empty, creating OSS client with default credentials chain for endpoint: {}",
              endpoint);

          // Use com.aliyun.credentials.Client to support default credentials chain (RRSA, ECS RAM Role, etc.)
          final Client credentialsClient = new Client();

          CredentialsProvider ossCredProvider =
              new CredentialsProvider() {
                private volatile Credentials currentCredentials;

                @Override
                public void setCredentials(Credentials credentials) {}

                @Override
                public Credentials getCredentials() {
                  try {
                    LOG.debug("Getting credentials using default credentials chain");
                    // getCredential() returns cached credentials and auto-refreshes when needed
                    CredentialModel cred = credentialsClient.getCredential();
                    long expirationSeconds = 0;
                    if (cred.getExpiration() > 0) {
                      expirationSeconds =
                          (cred.getExpiration() - System.currentTimeMillis()) / 1000;
                    }
                    this.currentCredentials =
                        new BasicCredentials(
                            cred.getAccessKeyId(),
                            cred.getAccessKeySecret(),
                            cred.getSecurityToken(),
                            expirationSeconds);
                    return this.currentCredentials;
                  } catch (Exception e) {
                    throw new RuntimeException("Failed to get credentials from default credentials chain", e);
                  }
                }
              };
          return new OSSClientBuilder().build(endpoint, ossCredProvider);
        } catch (Exception e) {
          throw new RuntimeException("Failed to create OSS client with default credentials chain", e);
        }
      } else if (Strings.isNullOrEmpty(aliyunProperties.securityToken())) {
        return new OSSClientBuilder()
            .build(
                aliyunProperties.ossEndpoint(),
                aliyunProperties.accessKeyId(),
                aliyunProperties.accessKeySecret());
      } else {
        return new OSSClientBuilder()
            .build(
                aliyunProperties.ossEndpoint(),
                aliyunProperties.accessKeyId(),
                aliyunProperties.accessKeySecret(),
                aliyunProperties.securityToken());
      }
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
