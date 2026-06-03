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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.io.ByteStreams;

class AliyunOSSMockHandler implements HttpHandler {

  private final AliyunOSSMockLocalStore localStore;

  AliyunOSSMockHandler(AliyunOSSMockLocalStore localStore) {
    this.localStore = localStore;
  }

  @Override
  public void handle(HttpExchange httpExchange) throws IOException {
    String request = httpExchange.getRequestURI().getPath().substring(1);
    String[] requests = request.split("/");
    String bucketName = requests[0];

    if (requests.length == 1) {
      handleBucketOperation(bucketName, httpExchange);
    } else {
      String objectName = requests[1];
      if (objectName.contains("?")) {
        objectName = objectName.substring(0, objectName.indexOf("?"));
      }
      handleObjectOperation(bucketName, objectName, httpExchange);
    }
  }

  private void handleBucketOperation(String bucketName, HttpExchange httpExchange)
      throws IOException {
    String method = httpExchange.getRequestMethod();
    if ("PUT".equals(method)) {
      putBucket(bucketName, httpExchange);
    } else if ("DELETE".equals(method)) {
      deleteBucket(bucketName, httpExchange);
    }
  }

  private void handleObjectOperation(
      String bucketName, String objectName, HttpExchange httpExchange) throws IOException {
    String method = httpExchange.getRequestMethod();
    if ("PUT".equals(method)) {
      putObject(bucketName, objectName, httpExchange);
    } else if ("DELETE".equals(method)) {
      deleteObject(bucketName, objectName, httpExchange);
    } else if ("HEAD".equals(method)) {
      getObjectMeta(bucketName, objectName, httpExchange);
    } else if ("GET".equals(method)) {
      getObject(bucketName, objectName, httpExchange);
    }
  }

  private void putBucket(String bucketName, HttpExchange httpExchange) throws IOException {
    if (localStore.getBucket(bucketName) != null) {
      String errorMessage =
          createErrorResponse(OSSErrorCode.BUCKET_ALREADY_EXISTS, bucketName + " already exists.");
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
      return;
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
    ObjectMetadata metadata = findObjectMetadata(bucketName, objectName);

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

    ObjectMetadata metadata = findObjectMetadata(bucketName, objectName);
    if (metadata == null) {
      String errorMessage =
          createErrorResponse(OSSErrorCode.NO_SUCH_KEY, "The specify oss key does not exists.");
      handleResponse(httpExchange, 404, errorMessage, "application/xml");
      return;
    }

    Object rangeHeader = httpExchange.getRequestHeaders().get("Range");
    if (rangeHeader != null) {
      getObjectWithRange(metadata, rangeHeader.toString(), httpExchange);
    } else {
      getObjectWithoutRange(metadata, httpExchange);
    }
  }

  private void getObjectWithRange(
      ObjectMetadata metadata, String rangeHeader, HttpExchange httpExchange) throws IOException {
    String rangeValue = rangeHeader.replace("[bytes=", "").replace("]", "");
    String[] ranges = rangeValue.split("-");
    long rangeStart = -1;
    if (!ranges[0].isEmpty()) {
      rangeStart = Long.parseLong(ranges[0]);
    }
    long rangeEnd = -1;
    if (ranges.length == 2 && !ranges[1].isEmpty()) {
      rangeEnd = Long.parseLong(ranges[1]);
    }

    long fileSize = metadata.getContentLength();
    long skipSize;
    long bytesToRead;

    if (rangeStart == -1 && rangeEnd == -1) {
      skipSize = 0;
      bytesToRead = fileSize;
    } else if (rangeStart == -1) {
      bytesToRead = Math.min(fileSize, rangeEnd);
      skipSize = fileSize - bytesToRead;
    } else if (rangeEnd == -1) {
      skipSize = rangeStart;
      bytesToRead = fileSize - rangeStart;
    } else {
      rangeEnd = Math.min(fileSize - 1, rangeEnd);
      skipSize = rangeStart;
      bytesToRead = rangeEnd - rangeStart + 1;
    }

    if (bytesToRead < 0 || fileSize < skipSize) {
      httpExchange.sendResponseHeaders(416, 1);
      return;
    }

    httpExchange.getResponseHeaders().add("Accept-Ranges", "bytes");
    httpExchange
        .getResponseHeaders()
        .add(
            "Content-Range",
            "bytes " + skipSize + "-" + (skipSize + bytesToRead - 1) + "/" + fileSize);
    httpExchange.getResponseHeaders().add("ETag", metadata.getContentMD5());
    httpExchange
        .getResponseHeaders()
        .add("Last-Modified", createDate(metadata.getLastModificationDate()));
    httpExchange.getResponseHeaders().add("Content-Type", metadata.getContentType());
    httpExchange.getResponseHeaders().add("Content-Length", Long.toString(bytesToRead));
    httpExchange.sendResponseHeaders(206, bytesToRead);

    try (OutputStream outputStream = httpExchange.getResponseBody()) {
      try (FileInputStream fis = new FileInputStream(metadata.getDataFile())) {
        fis.skip(skipSize);
        ByteStreams.copy(new BoundedInputStream(fis, bytesToRead), outputStream);
      }
    }
  }

  private void getObjectWithoutRange(ObjectMetadata metadata, HttpExchange httpExchange)
      throws IOException {
    httpExchange.getResponseHeaders().add("Accept-Ranges", "bytes");
    httpExchange.getResponseHeaders().add("ETag", metadata.getContentMD5());
    httpExchange
        .getResponseHeaders()
        .add("Last-Modified", createDate(metadata.getLastModificationDate()));
    httpExchange.getResponseHeaders().add("Content-Type", metadata.getContentType());
    httpExchange.sendResponseHeaders(200, metadata.getContentLength());

    try (OutputStream outputStream = httpExchange.getResponseBody()) {
      try (FileInputStream fis = new FileInputStream(metadata.getDataFile())) {
        ByteStreams.copy(fis, outputStream);
      }
    }
  }

  private void verifyBucketExistence(String bucketName, HttpExchange httpExchange)
      throws IOException {
    Bucket bucket = localStore.getBucket(bucketName);
    if (bucket == null) {
      String errorMessage =
          createErrorResponse(OSSErrorCode.NO_SUCH_BUCKET, "The specified bucket does not exist.");
      handleResponse(httpExchange, 404, errorMessage, "application/xml");
      throw new IOException("Bucket " + bucketName + " does not exist");
    }
  }

  private ObjectMetadata findObjectMetadata(String bucketName, String fileName) {
    try {
      return localStore.getObjectMetadata(bucketName, fileName);
    } catch (IOException e) {
      return null;
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

  private String createErrorResponse(String errorCode, String message) {
    StringBuilder builder = new StringBuilder();
    builder.append("<Error>");
    builder.append("<Code>").append(errorCode).append("</Code>");
    builder.append("<Message>").append(message).append("</Message>");
    builder.append("</Error>");
    return builder.toString();
  }

  private String createDate(long timestamp) {
    java.util.Date date = new java.util.Date(timestamp);
    ZonedDateTime dateTime = date.toInstant().atZone(ZoneId.of("GMT"));
    return dateTime.format(DateTimeFormatter.RFC_1123_DATE_TIME);
  }
}
