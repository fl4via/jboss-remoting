/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.remoting3;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.jboss.remoting3.spi.AbstractHandleableCloseable;
import org.jboss.remoting3.spi.ConnectionHandlerContext;
import org.xnio.IoUtils;
import org.xnio.Option;
import org.xnio.streams.Pipe;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class LocalChannel extends AbstractHandleableCloseable<Channel> implements Channel {
    private final Attachments attachments = new Attachments();
    private final LocalChannel otherSide;
    private final ConnectionHandlerContext connectionHandlerContext;
    private final Queue<In> messageQueue;
    private final Object lock = new Object();
    private final int queueLength;
    private final int bufferSize;

    private Receiver messageHandler;

    private boolean closed;

    LocalChannel(final Executor executor, final LocalChannel otherSide, final ConnectionHandlerContext connectionHandlerContext) {
        super(executor, true);
        this.otherSide = otherSide;
        this.connectionHandlerContext = connectionHandlerContext;
        queueLength = 8;
        messageQueue = new ArrayDeque<In>(queueLength);
        bufferSize = 8192;
    }

    LocalChannel(final Executor executor, final ConnectionHandlerContext connectionHandlerContext) {
        super(executor, true);
        this.connectionHandlerContext = connectionHandlerContext;
        otherSide = new LocalChannel(executor, this, connectionHandlerContext);
        queueLength = 8;
        messageQueue = new ArrayDeque<In>(queueLength);
        bufferSize = 8192;
    }

    public MessageOutputStream writeMessage() throws IOException {
        final LocalChannel otherSide = this.otherSide;
        final Queue<In> otherSideQueue = otherSide.messageQueue;
        synchronized (otherSide.lock) {
            for (;;) {
                if (otherSide.closed) {
                    throw new NotOpenException("Writes have been shut down");
                }
                final int size = otherSideQueue.size();
                if (size == queueLength) {
                    try {
                        otherSide.lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                    }
                } else {
                    final Pipe pipe = new Pipe(bufferSize);
                    In in = new In(pipe.getIn());
                    if (size == 0) {
                        final Receiver handler = otherSide.messageHandler;
                        if (handler != null) {
                            otherSide.messageHandler = null;
                            otherSide.lock.notify();
                            executeMessageTask(handler, in);
                            return new Out(pipe.getOut(), in);
                        }
                    }
                    otherSideQueue.add(in);
                    otherSide.lock.notify();
                    return new Out(pipe.getOut(), in);
                }
            }
        }
    }

    public void writeShutdown() throws IOException {
        final LocalChannel otherSide = this.otherSide;
        synchronized (otherSide.lock) {
            if (! otherSide.closed) {
                otherSide.closed = true;
                final Receiver messageHandler = otherSide.messageHandler;
                if (messageHandler != null && otherSide.messageQueue.isEmpty()) {
                    executeEndTask(messageHandler);
                } else {
                    otherSide.lock.notify();
                }
            }
        }
    }

    public void receiveMessage(final Receiver handler) {
        final Object lock = this.lock;
        synchronized (lock) {
            if (messageHandler != null) {
                throw new IllegalStateException("Message handler already waiting");
            }
            if (closed) {
                executeEndTask(handler);
            } else {
                final In in = messageQueue.poll();
                if (in != null) {
                    executeMessageTask(handler, in);
                } else {
                    messageHandler = handler;
                    lock.notify();
                }
            }
        }
    }

    public boolean supportsOption(final Option<?> option) {
        return false;
    }

    public <T> T getOption(final Option<T> option) {
        return null;
    }

    public <T> T setOption(final Option<T> option, final T value) throws IllegalArgumentException {
        return null;
    }

    private void executeEndTask(final Receiver handler) {
        getExecutor().execute(new Runnable() {
            public void run() {
                handler.handleEnd(LocalChannel.this);
            }
        });
    }

    private void executeMessageTask(final Receiver handler, final In in) {
        getExecutor().execute(new Runnable() {
            public void run() {
                handler.handleMessage(LocalChannel.this, in);
            }
        });
    }

    public Attachments getAttachments() {
        return attachments;
    }

    public Connection getConnection() {
        return connectionHandlerContext.getConnection();
    }

    protected void closeAction() throws IOException {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
        otherSide.connectionHandlerContext.remoteClosed();
        closeComplete();
    }

    LocalChannel getOtherSide() {
        return otherSide;
    }

    static final class Out extends MessageOutputStream {
        private final OutputStream outputStream;
        private final In in;

        Out(final OutputStream outputStream, final In in) {
            this.outputStream = outputStream;
            this.in = in;
        }

        public void flush() throws IOException {
            try {
                outputStream.flush();
            } catch (IOException e) {
                cancel();
                throw e;
            }
        }

        public void close() throws IOException {
            try {
                outputStream.close();
            } catch (IOException e) {
                cancel();
                throw e;
            }
        }

        public void write(final int b) throws IOException {
            try {
                outputStream.write(b);
            } catch (IOException e) {
                cancel();
                throw e;
            }
        }

        public void write(final byte[] b) throws IOException {
            try {
                outputStream.write(b);
            } catch (IOException e) {
                cancel();
                throw e;
            }
        }

        public void write(final byte[] b, final int off, final int len) throws IOException {
            try {
                outputStream.write(b, off, len);
            } catch (IOException e) {
                cancel();
                throw e;
            }
        }

        public Out cancel() {
            in.doCancel();
            IoUtils.safeClose(outputStream);
            return this;
        }
    }

    static final class In extends MessageInputStream {
        private final InputStream inputStream;
        private volatile boolean cancelled;

        In(final InputStream inputStream) {
            this.inputStream = inputStream;
        }

        public InputStream getInputStream() throws IOException {
            return inputStream;
        }

        synchronized void doCancel() {
            cancelled = true;
        }

        public synchronized boolean wasCancelled() {
            return cancelled;
        }

        public int read() throws IOException {
            checkCancel();
            return inputStream.read();
        }

        private synchronized void checkCancel() throws MessageCancelledException {
            if (cancelled) {
                throw new MessageCancelledException();
            }
        }

        public int read(final byte[] b) throws IOException {
            checkCancel();
            return inputStream.read(b);
        }

        public int read(final byte[] b, final int off, final int len) throws IOException {
            checkCancel();
            return inputStream.read(b, off, len);
        }

        public long skip(final long n) throws IOException {
            checkCancel();
            return inputStream.skip(n);
        }

        public int available() throws IOException {
            checkCancel();
            return inputStream.available();
        }

        public void close() throws IOException {
            checkCancel();
            inputStream.close();
        }

        public void mark(final int readlimit) {
            inputStream.mark(readlimit);
        }

        public void reset() throws IOException {
            checkCancel();
            inputStream.reset();
        }

        public boolean markSupported() {
            return inputStream.markSupported();
        }
    }
}
