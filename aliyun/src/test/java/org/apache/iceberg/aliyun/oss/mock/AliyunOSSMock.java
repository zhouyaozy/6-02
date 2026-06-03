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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.io.ByteStreams;

public class AliyunOSSMock {

  static final String PROP_ROOT_DIR = "root-dir";
  static final String ROOT_DIR_DEFAULT = "/tmp";

  static final String PROP_HTTP_PORT = "server.port";
  static final int PORT_HTTP_PORT_DEFAULT = 9393;

  private static final String APPLICATION_XML = "application/xml";
  private static final String TEXT_PLAIN = "text/plain";
  private static final String LOCALHOST = "localhost";
  private static final ZoneId GMT_ZONE_ID = ZoneId.of("GMT");

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
    this.localStore = new AliyunOSSMockLocalStore(rootDir);
    this.httpServer = HttpServer.create(new InetSocketAddress(LOCALHOST, serverPort), 0);
  }

  private void start() {
    httpServer.createContext("/", new AliyunHttpHandler(localStore));
    httpServer.start();
  }

  public void stop() {
    httpServer.stop(0);
  }

  private static class AliyunHttpHandler implements HttpHandler {
    private final AliyunOSSMockLocalStore localStore;

    private AliyunHttpHandler(AliyunOSSMockLocalStore localStore) {
      this.localStore = localStore;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
      RequestPath requestPath = RequestPath.from(httpExchange);
      if (requestPath.isBucketRequest()) {
        handleBucketRequest(requestPath.bucketName(), httpExchange);
      } else {
        handleObjectRequest(requestPath.bucketName(), requestPath.objectName(), httpExchange);
      }
    }

    private void handleBucketRequest(String bucketName, HttpExchange httpExchange)
        throws IOException {
      switch (httpExchange.getRequestMethod()) {
        case "PUT":
          putBucket(bucketName, httpExchange);
          break;
        case "DELETE":
          deleteBucket(bucketName, httpExchange);
          break;
        default:
      }
    }

    private void handleObjectRequest(
        String bucketName, String objectName, HttpExchange httpExchange) throws IOException {
      switch (httpExchange.getRequestMethod()) {
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
      }
    }

    private void putBucket(String bucketName, HttpExchange httpExchange) throws IOException {
      if (localStore.getBucket(bucketName) != null) {
        sendErrorResponse(
            httpExchange,
            409,
            OSSErrorCode.BUCKET_ALREADY_EXISTS,
            bucketName + " already exists.");
        return;
      }

      localStore.createBucket(bucketName);
      sendResponse(httpExchange, 200, "OK", APPLICATION_XML);
    }

    private void deleteBucket(String bucketName, HttpExchange httpExchange) throws IOException {
      if (!ensureBucketExists(bucketName, httpExchange)) {
        return;
      }

      try {
        localStore.deleteBucket(bucketName);
      } catch (Exception e) {
        sendErrorResponse(
            httpExchange,
            409,
            OSSErrorCode.BUCKET_NOT_EMPTY,
            "The bucket you tried to delete is not empty.");
        return;
      }

      sendResponse(httpExchange, 200, "OK", APPLICATION_XML);
    }

    private void putObject(String bucketName, String objectName, HttpExchange httpExchange)
        throws IOException {
      if (!ensureBucketExists(bucketName, httpExchange)) {
        return;
      }

      try (InputStream inputStream = httpExchange.getRequestBody()) {
        ObjectMetadata metadata =
            localStore.putObject(
                bucketName,
                objectName,
                inputStream,
                httpExchange.getRequestHeaders().getFirst("Content-Type"),
                httpExchange.getRequestHeaders().getFirst("Content-Headers"),
                ImmutableMap.of());

        addObjectHeaders(httpExchange, metadata);
        sendResponse(httpExchange, 200, "OK", TEXT_PLAIN);
      } catch (Exception e) {
        sendResponse(httpExchange, 500, "Internal Server Error", TEXT_PLAIN);
      }
    }

    private void deleteObject(String bucketName, String objectName, HttpExchange httpExchange)
        throws IOException {
      if (!ensureBucketExists(bucketName, httpExchange)) {
        return;
      }

      localStore.deleteObject(bucketName, objectName);
      sendResponse(httpExchange, 200, "OK", TEXT_PLAIN);
    }

    private void getObjectMeta(String bucketName, String objectName, HttpExchange httpExchange)
        throws IOException {
      if (!ensureBucketExists(bucketName, httpExchange)) {
        return;
      }

      ObjectMetadata metadata = getObjectMetadata(bucketName, objectName);
      if (metadata == null) {
        sendErrorResponse(
            httpExchange,
            404,
            OSSErrorCode.NO_SUCH_KEY,
            "The specify oss key does not exists.");
        return;
      }

      addObjectHeaders(httpExchange, metadata);
      httpExchange
          .getResponseHeaders()
          .add("Content-Length", Long.toString(metadata.getContentLength()));
      sendResponse(httpExchange, 200, "OK", TEXT_PLAIN);
    }

    private void getObject(String bucketName, String objectName, HttpExchange httpExchange)
        throws IOException {
      if (!ensureBucketExists(bucketName, httpExchange)) {
        return;
      }

      ObjectMetadata metadata = getObjectMetadata(bucketName, objectName);
      if (metadata == null) {
        sendErrorResponse(
            httpExchange,
            404,
            OSSErrorCode.NO_SUCH_KEY,
            "The specify oss key does not exists.");
        return;
      }

      RangeRequest rangeRequest = RangeRequest.from(httpExchange);
      if (rangeRequest == null) {
        getObject(metadata, httpExchange);
      } else {
        getObject(metadata, rangeRequest, httpExchange);
      }
    }

    private void getObject(ObjectMetadata metadata, HttpExchange httpExchange)
        throws IOException {
      httpExchange.getResponseHeaders().add("Accept-Ranges", "bytes");
      addObjectHeaders(httpExchange, metadata);
      httpExchange.sendResponseHeaders(200, metadata.getContentLength());

      try (OutputStream outputStream = httpExchange.getResponseBody();
          FileInputStream inputStream = new FileInputStream(metadata.getDataFile())) {
        ByteStreams.copy(inputStream, outputStream);
      }
    }

    private void getObject(
        ObjectMetadata metadata, RangeRequest rangeRequest, HttpExchange httpExchange)
        throws IOException {
      long rangeStart = rangeRequest.rangeStart();
      long rangeEnd = rangeRequest.rangeEnd();
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
      addObjectHeaders(httpExchange, metadata);
      httpExchange.getResponseHeaders().add("Content-Type", metadata.getContentType());
      httpExchange.getResponseHeaders().add("Content-Length", Long.toString(bytesToRead));
      httpExchange.sendResponseHeaders(206, bytesToRead);

      try (OutputStream outputStream = httpExchange.getResponseBody();
          FileInputStream inputStream = new FileInputStream(metadata.getDataFile())) {
        inputStream.skip(skipSize);
        ByteStreams.copy(new BoundedInputStream(inputStream, bytesToRead), outputStream);
      }
    }

    private boolean ensureBucketExists(String bucketName, HttpExchange httpExchange)
        throws IOException {
      Bucket bucket = localStore.getBucket(bucketName);
      if (bucket == null) {
        sendErrorResponse(
            httpExchange,
            404,
            OSSErrorCode.NO_SUCH_BUCKET,
            "The specified bucket does not exist.");
        return false;
      }

      return true;
    }

    private ObjectMetadata getObjectMetadata(String bucketName, String objectName) {
      try {
        return localStore.getObjectMetadata(bucketName, objectName);
      } catch (IOException e) {
        return null;
      }
    }

    private void addObjectHeaders(HttpExchange httpExchange, ObjectMetadata metadata) {
      httpExchange.getResponseHeaders().add("ETag", metadata.getContentMD5());
      httpExchange
          .getResponseHeaders()
          .add("Last-Modified", createDate(metadata.getLastModificationDate()));
    }

    private void sendResponse(
        HttpExchange httpExchange, int responseCode, String responsePayload, String contentType)
        throws IOException {
      byte[] responseBytes = responsePayload.getBytes();
      httpExchange.getResponseHeaders().put("Content-Type", Collections.singletonList(contentType));
      httpExchange.sendResponseHeaders(responseCode, responseBytes.length);
      try (OutputStream outputStream = httpExchange.getResponseBody()) {
        outputStream.write(responseBytes);
        outputStream.flush();
      }
    }

    private void sendErrorResponse(
        HttpExchange httpExchange, int responseCode, String errorCode, String message)
        throws IOException {
      sendResponse(httpExchange, responseCode, createErrorResponse(errorCode, message), APPLICATION_XML);
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
      ZonedDateTime dateTime = new java.util.Date(timestamp).toInstant().atZone(GMT_ZONE_ID);
      return dateTime.format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }
  }

  private static class RequestPath {
    private final String bucketName;
    private final String objectName;

    private RequestPath(String bucketName, String objectName) {
      this.bucketName = bucketName;
      this.objectName = objectName;
    }

    private static RequestPath from(HttpExchange httpExchange) {
      String request = httpExchange.getRequestURI().getPath().substring(1);
      String[] requests = request.split("/");
      String bucketName = requests[0];
      if (requests.length == 1) {
        return new RequestPath(bucketName, null);
      }

      String objectName = requests[1];
      if (objectName.contains("?")) {
        objectName = objectName.substring(0, objectName.indexOf('?'));
      }

      return new RequestPath(bucketName, objectName);
    }

    private String bucketName() {
      return bucketName;
    }

    private String objectName() {
      return objectName;
    }

    private boolean isBucketRequest() {
      return objectName == null;
    }
  }

  private static class RangeRequest {
    private final long rangeStart;
    private final long rangeEnd;

    private RangeRequest(long rangeStart, long rangeEnd) {
      this.rangeStart = rangeStart;
      this.rangeEnd = rangeEnd;
    }

    private static RangeRequest from(HttpExchange httpExchange) {
      String rangeHeader = httpExchange.getRequestHeaders().getFirst("Range");
      if (rangeHeader == null) {
        return null;
      }

      String[] ranges = rangeHeader.replace("bytes=", "").split("-");
      long rangeStart = -1;
      if (!ranges[0].isEmpty()) {
        rangeStart = Long.parseLong(ranges[0]);
      }

      long rangeEnd = -1;
      if (ranges.length == 2 && !ranges[1].isEmpty()) {
        rangeEnd = Long.parseLong(ranges[1]);
      }

      return new RangeRequest(rangeStart, rangeEnd);
    }

    private long rangeStart() {
      return rangeStart;
    }

    private long rangeEnd() {
      return rangeEnd;
    }
  }

  /**
   * Reads bytes up to a maximum length, if its count goes above that, it stops.
   *
   * <p>This is useful to wrap ServletInputStreams. The ServletInputStream will block if you try to
   * read content from it that isn't there, because it doesn't know whether the content hasn't
   * arrived yet or whether the content has finished. So, one of these, initialized with the
   * Content-length sent in the ServletInputStream's header, will stop it blocking, providing it's
   * been sent with a correct content length.
   *
   * <p>This code is borrowed from `org.apache.commons:commons-io`
   */
  public static class BoundedInputStream extends FilterInputStream {

    /** The max count of bytes to read. */
    private final long maxCount;

    /** The count of bytes read. */
    private long count;

    /** The marked position. */
    private long mark = -1;

    /** Flag if close should be propagated. */
    private boolean propagateClose = true;

    /**
     * Constructs a new {@link BoundedInputStream} that wraps the given input stream and is
     * unlimited.
     *
     * @param in The wrapped input stream.
     */
    public BoundedInputStream(final InputStream in) {
      this(in, -1);
    }

    /**
     * Constructs a new {@link BoundedInputStream} that wraps the given input stream and limits it
     * to a certain size.
     *
     * @param inputStream The wrapped input stream.
     * @param maxLength The maximum number of bytes to return.
     */
    public BoundedInputStream(final InputStream inputStream, final long maxLength) {
      // Some badly designed methods - e.g. the servlet API - overload length
      // such that "-1" means stream finished
      super(inputStream);
      this.maxCount = maxLength;
    }

    /** {@inheritDoc} */
    @Override
    public int available() throws IOException {
      if (isMaxLength()) {
        onMaxLength(maxCount, count);
        return 0;
      }
      return in.available();
    }

    /**
     * Invokes the delegate's {@code close()} method if {@link #isPropagateClose()} is {@code true}.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
      if (propagateClose) {
        in.close();
      }
    }

    /**
     * Gets the count of bytes read.
     *
     * @return The count of bytes read.
     * @since 2.12.0
     */
    public long getCount() {
      return count;
    }

    /**
     * Gets the max count of bytes to read.
     *
     * @return The max count of bytes to read.
     * @since 2.12.0
     */
    public long getMaxLength() {
      return maxCount;
    }

    private boolean isMaxLength() {
      return maxCount >= 0 && count >= maxCount;
    }

    /**
     * Tests whether the {@link #close()} method should propagate to the underling {@link
     * InputStream}.
     *
     * @return {@code true} if calling {@link #close()} propagates to the {@code close()} method of
     *     the underlying stream or {@code false} if it does not.
     */
    public boolean isPropagateClose() {
      return propagateClose;
    }

    /**
     * Sets whether the {@link #close()} method should propagate to the underling {@link
     * InputStream}.
     *
     * @param propagateClose {@code true} if calling {@link #close()} propagates to the {@code
     *     close()} method of the underlying stream or {@code false} if it does not.
     */
    public void setPropagateClose(final boolean propagateClose) {
      this.propagateClose = propagateClose;
    }

    /**
     * Invokes the delegate's {@code mark(int)} method.
     *
     * @param readlimit read ahead limit
     */
    @Override
    public synchronized void mark(final int readlimit) {
      in.mark(readlimit);
      mark = count;
    }

    /**
     * Invokes the delegate's {@code markSupported()} method.
     *
     * @return true if mark is supported, otherwise false
     */
    @Override
    public boolean markSupported() {
      return in.markSupported();
    }

    /**
     * A caller has caused a request that would cross the {@code maxLength} boundary.
     *
     * @param maxLength The max count of bytes to read.
     * @param bytesRead The count of bytes read.
     * @throws IOException Subclasses may throw.
     * @since 2.12.0
     */
    protected void onMaxLength(final long maxLength, final long bytesRead) throws IOException {
      // for subclasses
    }

    /**
     * Invokes the delegate's {@code read()} method if the current position is less than the limit.
     *
     * @return the byte read or -1 if the end of stream or the limit has been reached.
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public int read() throws IOException {
      if (isMaxLength()) {
        onMaxLength(maxCount, count);
        return -1;
      }
      final int result = in.read();
      count++;
      return result;
    }

    /**
     * Invokes the delegate's {@code read(byte[])} method.
     *
     * @param b the buffer to read the bytes into
     * @return the number of bytes read or -1 if the end of stream or the limit has been reached.
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public int read(final byte[] b) throws IOException {
      return this.read(b, 0, b.length);
    }

    /**
     * Invokes the delegate's {@code read(byte[], int, int)} method.
     *
     * @param b the buffer to read the bytes into
     * @param off The start offset
     * @param len The number of bytes to read
     * @return the number of bytes read or -1 if the end of stream or the limit has been reached.
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
      if (isMaxLength()) {
        onMaxLength(maxCount, count);
        return -1;
      }
      final long maxRead = maxCount >= 0 ? Math.min(len, maxCount - count) : len;
      final int bytesRead = in.read(b, off, (int) maxRead);

      if (bytesRead == -1) {
        return -1;
      }

      count += bytesRead;
      return bytesRead;
    }

    /**
     * Invokes the delegate's {@code reset()} method.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public synchronized void reset() throws IOException {
      in.reset();
      count = mark;
    }

    /**
     * Invokes the delegate's {@code skip(long)} method.
     *
     * @param n the number of bytes to skip
     * @return the actual number of bytes skipped
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public long skip(final long n) throws IOException {
      final long toSkip = maxCount >= 0 ? Math.min(n, maxCount - count) : n;
      final long skippedBytes = in.skip(toSkip);
      count += skippedBytes;
      return skippedBytes;
    }

    /**
     * Invokes the delegate's {@code toString()} method.
     *
     * @return the delegate's {@code toString()}
     */
    @Override
    public String toString() {
      return in.toString();
    }
  }
}
