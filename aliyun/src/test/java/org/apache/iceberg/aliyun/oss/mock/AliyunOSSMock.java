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

import com.aliyun.oss.OSSErrorCode;
import com.aliyun.oss.model.Bucket;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.io.ByteStreams;

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
    httpServer.createContext("/", new AliyunHttpHandler());
    httpServer.start();
  }

  public void stop() {
    httpServer.stop(0);
  }

  private class AliyunHttpHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      String method = httpExchange.getRequestMethod().toUpperCase(Locale.ROOT);
      String path = httpExchange.getRequestURI().getPath().substring(1);
      String[] segments = path.split("/");
      String bucketName = segments[0];

      if (segments.length == 1) {
        handleBucketOperation(bucketName, method, httpExchange);
      } else {
        String objectName = segments[1];
        if (objectName.contains("?")) {
          objectName = objectName.substring(0, objectName.indexOf("?"));
        }
        handleObjectOperation(bucketName, objectName, method, httpExchange);
      }
    }

    private void handleBucketOperation(
        String bucketName, String method, HttpExchange httpExchange) throws IOException {
      switch (method) {
        case "PUT":
          putBucket(bucketName, httpExchange);
          break;
        case "DELETE":
          deleteBucket(bucketName, httpExchange);
          break;
        default:
          handleResponse(httpExchange, 405, "Method Not Allowed", "text/plain");
      }
    }

    private void handleObjectOperation(
        String bucketName, String objectName, String method, HttpExchange httpExchange)
        throws IOException {
      switch (method) {
        case "PUT":
          putObject(bucketName, objectName, httpExchange);
          break;
        case "DELETE":
          deleteObject(bucketName, objectName, httpExchange);
          break;
        case "HEAD":
          getObjectMeta(bucketName, objectName, httpExchange);
          break;
        case "GET":
          getObject(bucketName, objectName, httpExchange);
          break;
        default:
          handleResponse(httpExchange, 405, "Method Not Allowed", "text/plain");
      }
    }

    private void putBucket(String bucketName, HttpExchange httpExchange) throws IOException {
      if (localStore.getBucket(bucketName) != null) {
        String errorMessage =
            createErrorResponse(
                OSSErrorCode.BUCKET_ALREADY_EXISTS, bucketName + " already exists.");
        handleResponse(httpExchange, 409, errorMessage, "application/xml");
        return;
      }
      localStore.createBucket(bucketName);
      handleResponse(httpExchange, 200, "OK", "application/xml");
    }

    private void deleteBucket(String bucketName, HttpExchange httpExchange) throws IOException {
      verifyBucketExistence(bucketName, httpExchange);
      try {
        localStore.deleteBucket(bucketName);
      } catch (Exception e) {
        String errorMessage =
            createErrorResponse(
                OSSErrorCode.BUCKET_NOT_EMPTY, "The bucket you tried to delete is not empty.");
        handleResponse(httpExchange, 409, errorMessage, "application/xml");
      }
      handleResponse(httpExchange, 200, "OK", "application/xml");
    }

    private void putObject(String bucketName, String objectName, HttpExchange httpExchange)
        throws IOException {
      verifyBucketExistence(bucketName, httpExchange);

      try (InputStream inputStream = httpExchange.getRequestBody()) {
        ObjectMetadata metadata =
            localStore.putObject(
                bucketName,
                objectName,
                inputStream,
                httpExchange.getRequestHeaders().getFirst("Content-Type"),
                httpExchange.getRequestHeaders().getFirst("Content-Headers"),
                ImmutableMap.of());

        httpExchange.getResponseHeaders().add("ETag", metadata.getContentMD5());
        httpExchange
            .getResponseHeaders()
            .add("Last-Modified", createDate(metadata.getLastModificationDate()));
        handleResponse(httpExchange, 200, "OK", "text/plain");
      } catch (Exception e) {
        handleResponse(httpExchange, 500, "Internal Server Error", "text/plain");
      }
    }

    private void deleteObject(String bucketName, String objectName, HttpExchange httpExchange)
        throws IOException {
      verifyBucketExistence(bucketName, httpExchange);
      localStore.deleteObject(bucketName, objectName);

      handleResponse(httpExchange, 200, "OK", "text/plain");
    }

    private void getObjectMeta(String bucketName, String objectName, HttpExchange httpExchange)
        throws IOException {
      verifyBucketExistence(bucketName, httpExchange);
      ObjectMetadata metadata = localStore.getObjectMetadata(bucketName, objectName);

      if (metadata == null) {
        String errorMessage =
            createErrorResponse(OSSErrorCode.NO_SUCH_KEY, "The specify oss key does not exists.");
        handleResponse(httpExchange, 404, errorMessage, "application/xml");
      } else {
        httpExchange.getResponseHeaders().add("ETag", metadata.getContentMD5());
        httpExchange
            .getResponseHeaders()
            .add("Last-Modified", createDate(metadata.getLastModificationDate()));
        httpExchange
            .getResponseHeaders()
            .add("Content-Length", Long.toString(metadata.getContentLength()));

        handleResponse(httpExchange, 200, "OK", "text/plain");
      }
    }

    private void getObject(String bucketName, String objectName, HttpExchange httpExchange)
        throws IOException {
      verifyBucketExistence(bucketName, httpExchange);

      ObjectMetadata metadata = localStore.getObjectMetadata(bucketName, objectName);
      if (metadata == null) {
        String errorMessage =
            createErrorResponse(OSSErrorCode.NO_SUCH_KEY, "The specify oss key does not exists.");
        handleResponse(httpExchange, 404, errorMessage, "application/xml");
        return;
      }

      String rangeHeader = httpExchange.getRequestHeaders().getFirst("Range");
      if (rangeHeader != null) {
        writeObjectWithRange(metadata, rangeHeader, httpExchange);
      } else {
        writeObjectFull(metadata, httpExchange);
      }
    }

    private void writeObjectFull(ObjectMetadata metadata, HttpExchange httpExchange)
        throws IOException {
      httpExchange.getResponseHeaders().add("Accept-Ranges", "bytes");
      httpExchange.getResponseHeaders().add("ETag", metadata.getContentMD5());
      httpExchange
          .getResponseHeaders()
          .add("Last-Modified", createDate(metadata.getLastModificationDate()));
      httpExchange.getResponseHeaders().add("Content-Type", metadata.getContentType());
      httpExchange.sendResponseHeaders(200, metadata.getContentLength());

      try (OutputStream outputStream = httpExchange.getResponseBody();
          FileInputStream fis = new FileInputStream(metadata.getDataFile())) {
        ByteStreams.copy(fis, outputStream);
      }
    }

    private void writeObjectWithRange(
        ObjectMetadata metadata, String rangeHeader, HttpExchange httpExchange)
        throws IOException {
      String rangeStr = rangeHeader.replace("[bytes=", "").replace("]", "");
      String[] rangeParts = rangeStr.split("-");

      long rangeStart = rangeParts[0].isEmpty() ? -1 : Long.parseLong(rangeParts[0]);
      long rangeEnd = rangeParts.length == 2 && !rangeParts[1].isEmpty()
          ? Long.parseLong(rangeParts[1])
          : -1;

      if (rangeEnd == -1 && rangeStart != -1) {
        rangeEnd = metadata.getContentLength() - 1;
      }
      if (rangeStart == -1 && rangeEnd != -1) {
        rangeStart = metadata.getContentLength() - rangeEnd;
        rangeEnd = metadata.getContentLength() - 1;
      }
      if (rangeStart == -1) {
        rangeStart = 0;
        rangeEnd = metadata.getContentLength() - 1;
      }

      long bytesToRead = rangeEnd - rangeStart + 1;
      if (bytesToRead <= 0 || rangeStart >= metadata.getContentLength()) {
        httpExchange.sendResponseHeaders(416, 1);
        return;
      }

      httpExchange.getResponseHeaders().add("Accept-Ranges", "bytes");
      httpExchange
          .getResponseHeaders()
          .add(
              "Content-Range",
              "bytes "
                  + rangeStart
                  + "-"
                  + rangeEnd
                  + "/"
                  + metadata.getContentLength());
      httpExchange.getResponseHeaders().add("ETag", metadata.getContentMD5());
      httpExchange
          .getResponseHeaders()
          .add("Last-Modified", createDate(metadata.getLastModificationDate()));
      httpExchange.getResponseHeaders().add("Content-Type", metadata.getContentType());
      httpExchange.getResponseHeaders().add("Content-Length", Long.toString(bytesToRead));
      httpExchange.sendResponseHeaders(206, bytesToRead);

      try (OutputStream outputStream = httpExchange.getResponseBody();
          FileInputStream fis = new FileInputStream(metadata.getDataFile())) {
        fis.skip(rangeStart);
        ByteStreams.copy(new BoundedInputStream(fis, bytesToRead), outputStream);
      }
    }

    private void verifyBucketExistence(String bucketName, HttpExchange httpExchange)
        throws IOException {
      Bucket bucket = localStore.getBucket(bucketName);
      if (bucket == null) {
        String errorMessage =
            createErrorResponse(
                OSSErrorCode.NO_SUCH_BUCKET, "The specified bucket does not exist.");
        handleResponse(httpExchange, 404, errorMessage, "application/xml");
      }
    }

    private void handleResponse(
        HttpExchange httpExchange, int responseCode, String responsePayload, String contentType)
        throws IOException {
      OutputStream outputStream = httpExchange.getResponseBody();
      httpExchange.getResponseHeaders().put("Content-Type", Collections.singletonList(contentType));
      httpExchange.sendResponseHeaders(responseCode, responsePayload.length());
      outputStream.write(responsePayload.getBytes());
      outputStream.flush();
      outputStream.close();
    }

    private static String createErrorResponse(String errorCode, String message) {
      StringBuilder builder = new StringBuilder();
      builder.append("<Error>");
      builder.append("<Code>").append(errorCode).append("</Code>");
      builder.append("<Message>").append(message).append("</Message>");
      builder.append("</Error>");
      return builder.toString();
    }

    private static String createDate(long timestamp) {
      java.util.Date date = new java.util.Date(timestamp);
      ZonedDateTime dateTime = date.toInstant().atZone(ZoneId.of("GMT"));
      return dateTime.format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }
  }
}
