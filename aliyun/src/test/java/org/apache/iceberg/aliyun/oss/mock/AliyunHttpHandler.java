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

class AliyunHttpHandler implements HttpHandler {

  private final AliyunOSSMockLocalStore localStore;

  AliyunHttpHandler(AliyunOSSMockLocalStore localStore) {
    this.localStore = localStore;
  }

  @Override
  public void handle(HttpExchange httpExchange) throws IOException {
    String request = httpExchange.getRequestURI().getPath().substring(1);
    String[] requests = request.split("/");
    String bucketName = requests[0];
    if (requests.length == 1) {
      // bucket operations
      if (httpExchange.getRequestMethod().equals("PUT")) {
        putBucket(bucketName, httpExchange);
      }
      if (httpExchange.getRequestMethod().equals("DELETE")) {
        deleteBucket(bucketName, httpExchange);
      }
    } else {
      // object operations
      String objectName = requests[1];
      if (objectName.contains("?")) {
        objectName = objectName.substring(0, objectName.indexOf("?"));
      }
      if (httpExchange.getRequestMethod().equals("PUT")) {
        putObject(bucketName, objectName, httpExchange);
      }
      if (httpExchange.getRequestMethod().equals("DELETE")) {
        deleteObject(bucketName, objectName, httpExchange);
      }
      if (httpExchange.getRequestMethod().equals("HEAD")) {
        getObjectMeta(bucketName, objectName, httpExchange);
      }
      if (httpExchange.getRequestMethod().equals("GET")) {
        getObject(bucketName, objectName, httpExchange);
      }
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
    ObjectMetadata metadata = verifyObjectExistence(bucketName, objectName);

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

    String filename = objectName;
    ObjectMetadata metadata = verifyObjectExistence(bucketName, filename);

    if (metadata == null) {
      String errorMessage =
          createErrorResponse(OSSErrorCode.NO_SUCH_KEY, "The specify oss key does not exists.");
      handleResponse(httpExchange, 404, errorMessage, "application/xml");
      return;
    }

    Object range = httpExchange.getRequestHeaders().get("Range");
    if (range != null) {
      range = range.toString().replace("[bytes=", "").replace("]", "");
      String[] ranges = range.toString().split("-");
      long rangeStart = -1;
      if (!ranges[0].isEmpty()) {
        rangeStart = Long.parseLong(ranges[0]);
      }
      long rangeEnd = -1;
      if (ranges.length == 2 && !ranges[1].isEmpty()) {
        rangeEnd = Long.parseLong(ranges[1]);
      }
      if (rangeEnd == -1) {
        rangeEnd = Long.MAX_VALUE;
        if (rangeStart == -1) {
          rangeStart = 0;
        }
      }

      long fileSize = metadata.getContentLength();
      long bytesToRead = Math.min(fileSize - 1, rangeEnd) - rangeStart + 1;
      long skipSize = rangeStart;
      if (rangeStart == -1) {
        bytesToRead = Math.min(fileSize - 1, rangeEnd);
        skipSize = fileSize - rangeEnd;
      }
      if (rangeEnd == -1) {
        bytesToRead = fileSize - rangeStart;
      }
      if (bytesToRead < 0 || fileSize < rangeStart) {
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
                  + (bytesToRead + rangeStart + 1)
                  + "/"
                  + metadata.getContentLength());
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
    } else {
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
  }

  private void verifyBucketExistence(String bucketName, HttpExchange httpExchange)
      throws IOException {
    Bucket bucket = localStore.getBucket(bucketName);
    if (bucket == null) {
      String errorMessage =
          createErrorResponse(OSSErrorCode.NO_SUCH_BUCKET, "The specified bucket does not exist.");
      handleResponse(httpExchange, 404, errorMessage, "application/xml");
    }
  }

  private ObjectMetadata verifyObjectExistence(String bucketName, String fileName) {
    ObjectMetadata objectMetadata = null;
    try {
      objectMetadata = localStore.getObjectMetadata(bucketName, fileName);
    } catch (IOException e) {
      // no-op
    }

    return objectMetadata;
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
