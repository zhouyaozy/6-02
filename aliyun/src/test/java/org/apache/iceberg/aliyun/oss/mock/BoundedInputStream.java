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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

class BoundedInputStream extends FilterInputStream {

  private final long maxCount;
  private long count;
  private long mark = -1;
  private boolean propagateClose = true;

  BoundedInputStream(final InputStream in) {
    this(in, -1);
  }

  BoundedInputStream(final InputStream inputStream, final long maxLength) {
    super(inputStream);
    this.maxCount = maxLength;
  }

  @Override
  public int available() throws IOException {
    if (isMaxLength()) {
      onMaxLength(maxCount, count);
      return 0;
    }
    return in.available();
  }

  @Override
  public void close() throws IOException {
    if (propagateClose) {
      in.close();
    }
  }

  long getCount() {
    return count;
  }

  long getMaxLength() {
    return maxCount;
  }

  private boolean isMaxLength() {
    return maxCount >= 0 && count >= maxCount;
  }

  boolean isPropagateClose() {
    return propagateClose;
  }

  void setPropagateClose(final boolean propagateClose) {
    this.propagateClose = propagateClose;
  }

  @Override
  public synchronized void mark(final int readlimit) {
    in.mark(readlimit);
    mark = count;
  }

  @Override
  public boolean markSupported() {
    return in.markSupported();
  }

  protected void onMaxLength(final long maxLength, final long bytesRead) throws IOException {}

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

  @Override
  public int read(final byte[] b) throws IOException {
    return this.read(b, 0, b.length);
  }

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

  @Override
  public synchronized void reset() throws IOException {
    in.reset();
    count = mark;
  }

  @Override
  public long skip(final long n) throws IOException {
    final long toSkip = maxCount >= 0 ? Math.min(n, maxCount - count) : n;
    final long skippedBytes = in.skip(toSkip);
    count += skippedBytes;
    return skippedBytes;
  }

  @Override
  public String toString() {
    return in.toString();
  }
}
