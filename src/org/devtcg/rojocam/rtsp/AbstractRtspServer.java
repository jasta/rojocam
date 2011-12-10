/*
 * Copyright (C) 2011 Josh Guilfoyle <jasta@devtcg.org>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */

package org.devtcg.rojocam.rtsp;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.Header;
import org.apache.http.HttpConnection;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestFactory;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpServerConnection;
import org.apache.http.HttpStatus;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.impl.io.HttpRequestParser;
import org.apache.http.io.HttpMessageParser;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicLineParser;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.DefaultedHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerRegistry;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;

/**
 * Basic structure of an RTSP server using HttpClient. Should be extended to
 * respond to requests made by clients.
 * <p>
 * HttpClient is surprisingly flexible to allow this kind of voodoo. Kudos
 * Apache, Kudos.
 */
public abstract class AbstractRtspServer extends Thread {
    public static final String TAG = AbstractRtspServer.class.getSimpleName();

    protected final HashSet<WorkerThread> mWorkers =
            new HashSet<WorkerThread>();

    protected ServerSocket mSocket;
    protected final HttpParams mParams;
    private HttpRequestHandler mReqHandler;

    private volatile boolean mShutdown;

    public AbstractRtspServer() {
        super(TAG);

        /*
         * XXX: Android as a client seems very unhappy if the RTSP connection is
         * closed so we must use an SO_TIMEOUT of infinite to avoid that. The
         * client can close when it wants to.
         */
        mParams = new BasicHttpParams()
                .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 0)
                .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 16384)
                .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
                .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true);

        setDaemon(true);
    }

    public void bind(InetSocketAddress addr) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(addr);
        mSocket = socket;
        Log.i(TAG, "Bound to port " + mSocket.getLocalPort());
    }

    public void setRequestHandler(HttpRequestHandler handler) {
        mReqHandler = handler;
    }

    private void checkIsBound() {
        if (mSocket == null) {
            throw new IllegalStateException("Not bound.");
        }
    }

    public int getPort() {
        checkIsBound();
        return mSocket.getLocalPort();
    }

    public void shutdown() {
        checkIsBound();

        onPreShutdown();

        mShutdown = true;

        synchronized(mWorkers) {
            for (WorkerThread worker: mWorkers) {
                worker.shutdown();
            }
        }

        try {
            mSocket.close();
        } catch (IOException e) {
        }
    }

    protected void onPreShutdown() {
    }

    public void run() {
        checkIsBound();
        if (mReqHandler == null) {
            throw new IllegalStateException("Request handler not set.");
        }

        while (!mShutdown) {
            try {
                Socket sock = mSocket.accept();
                RtspServerConnection conn = new RtspServerConnection();

                conn.bind(sock, mParams);

                BasicHttpProcessor processor = new BasicHttpProcessor();
                processor.addInterceptor(new ResponseContent());
                processor.addInterceptor(new ResponseHeaderEcho(RtspHeaders.CSEQ));
                processor.addInterceptor(new ResponseDate());
                processor.addInterceptor(new ResponseHeaderEcho(RtspHeaders.SESSION));

                HttpRequestHandlerRegistry reg = new HttpRequestHandlerRegistry();
                reg.register("*", mReqHandler);

                RtspService svc = new RtspService(processor,
                        new RtspConnectionReuseStrategy(), new DefaultHttpResponseFactory());

                svc.setParams(mParams);
                svc.setHandlerResolver(reg);

                WorkerThread worker = new WorkerThread(svc, conn);

                synchronized(mWorkers) {
                    mWorkers.add(worker);
                }

                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (!mShutdown) {
                    Log.e(TAG, "I/O error initializing connection thread", e);
                }
                break;
            }
        }
    }

    private class WorkerThread extends Thread {
        private final RtspService mService;
        private final RtspServerConnection mConn;

        public WorkerThread(RtspService svc, RtspServerConnection conn) {
            super();
            mService = svc;
            mConn = conn;
        }

        public void run() {
            HttpContext ctx = new BasicHttpContext(null);

            try {
                while (!mShutdown && mConn.isOpen()) {
                    mService.handleRequest(mConn, ctx);
                }
            } catch (Exception e) {
                if (!mShutdown) {
                    Log.e(TAG, "RTSP server disrupted: " + e.toString());
                }
            } finally {
                if (!mShutdown) {
                    try {
                        mConn.shutdown();
                    } catch (IOException e) {
                    }

                    synchronized(mWorkers) {
                        mWorkers.remove(this);
                    }
                }
            }
        }

        public void shutdown() {
            try {
                mConn.shutdown();
            } catch (IOException e) {
            }
        }
    }

    private static class RtspService extends HttpService {
        private HttpProcessor processor;
        private ConnectionReuseStrategy connStrategy;
        private HttpResponseFactory responseFactory;

        public RtspService(HttpProcessor proc, ConnectionReuseStrategy connStrategy,
                HttpResponseFactory responseFactory) {
            super(proc, connStrategy, responseFactory);
        }

        @Override
        public void setHttpProcessor(HttpProcessor processor) {
            super.setHttpProcessor(processor);
            this.processor = processor;
        }

        @Override
        public void setConnReuseStrategy(ConnectionReuseStrategy connStrategy) {
            super.setConnReuseStrategy(connStrategy);
            this.connStrategy = connStrategy;
        }

        @Override
        public void setResponseFactory(HttpResponseFactory responseFactory) {
            super.setResponseFactory(responseFactory);
            this.responseFactory = responseFactory;
        }

        /*
         * XXX: Most of this method was copied from HttpService's handleRequest
         * from Android version 2.3.1_r1. The reason for the copy was to remove
         * hardcoded HTTP protocol assumptions.
         */
        @Override
        public void handleRequest(HttpServerConnection conn, HttpContext context)
                throws IOException, HttpException {
            context.setAttribute(ExecutionContext.HTTP_CONNECTION, conn);

            HttpResponse response = null;

            try {
                HttpRequest request = conn.receiveRequestHeader();
                request.setParams(
                        new DefaultedHttpParams(request.getParams(), getParams()));

                ProtocolVersion ver =
                    request.getRequestLine().getProtocolVersion();

                response = this.responseFactory.newHttpResponse(ver, HttpStatus.SC_OK, context);
                response.setParams(
                        new DefaultedHttpParams(response.getParams(), getParams()));

                context.setAttribute(ExecutionContext.HTTP_REQUEST, request);
                context.setAttribute(ExecutionContext.HTTP_RESPONSE, response);

                this.processor.process(request, context);
                doService(request, response, context);
            } catch (HttpException ex) {
                response = this.responseFactory.newHttpResponse
                    (RtspVersion.RTSP_1_0, HttpStatus.SC_INTERNAL_SERVER_ERROR,
                     context);
                response.setParams(
                        new DefaultedHttpParams(response.getParams(), getParams()));
                handleException(ex, response);
            }

            this.processor.process(response, context);
            conn.sendResponseHeader(response);
            conn.sendResponseEntity(response);
            conn.flush();

            if (!this.connStrategy.keepAlive(response, context)) {
                conn.close();
            }
        }
    }

    private static class RtspServerConnection extends DefaultHttpServerConnection {
        @Override
        protected HttpMessageParser createRequestParser(SessionInputBuffer buffer,
                HttpRequestFactory requestFactory, HttpParams params) {
            return new HttpRequestParser(buffer, new BasicLineParser(RtspVersion.RTSP_1_0),
                    requestFactory, params);
        }

        @Override
        protected HttpRequestFactory createHttpRequestFactory() {
            return new RtspRequestFactory();
        }
    }

    private static class RtspRequestFactory implements HttpRequestFactory {
        private static final String[] SUPPORTED_METHODS = {
            RtspMethods.OPTIONS,
            RtspMethods.DESCRIBE,
            RtspMethods.PAUSE,
            RtspMethods.PLAY,
            RtspMethods.SETUP,
            RtspMethods.TEARDOWN,
        };

        private static boolean isOneOf(final String[] haystack, final String needle) {
            for (int i = 0; i < haystack.length; i++) {
                if (haystack[i].equalsIgnoreCase(needle)) {
                    return true;
                }
            }
            return false;
        }

        public HttpRequest newHttpRequest(RequestLine requestline)
                throws MethodNotSupportedException {
            String method = requestline.getMethod();
            if (isOneOf(SUPPORTED_METHODS, method)) {
                return new BasicHttpRequest(requestline);
            } else {
                throw new MethodNotSupportedException(method +  " method not supported");
            }
        }

        public HttpRequest newHttpRequest(String method, String uri)
                throws MethodNotSupportedException {
            if (isOneOf(SUPPORTED_METHODS, method)) {
                return new BasicHttpRequest(method, uri);
            } else {
                throw new MethodNotSupportedException(method +  " method not supported");
            }
        }

    }

    private static class RtspConnectionReuseStrategy implements ConnectionReuseStrategy {
        public boolean keepAlive(HttpResponse response, HttpContext context) {
            HttpConnection conn = (HttpConnection)
                    context.getAttribute(ExecutionContext.HTTP_CONNECTION);

            if (conn != null && !conn.isOpen()) {
                return false;
            }

            return true;
        }
    }

    @SuppressWarnings("serial")
    private static class RtspVersion extends ProtocolVersion {
        public static final RtspVersion RTSP_1_0 = new RtspVersion(1, 0);

        public RtspVersion(int major, int minor) {
            super("RTSP", major, minor);
        }
    }

    /**
     * Simple response interceptor that echoes back a specific header sent by
     * the request if present.
     */
    private static class ResponseHeaderEcho implements HttpResponseInterceptor {
        private final String mHeader;

        public ResponseHeaderEcho(String header) {
            mHeader = header;
        }

        public void process(HttpResponse response, HttpContext context) throws HttpException,
                IOException {
            HttpRequest request = (HttpRequest)context.getAttribute(ExecutionContext.HTTP_REQUEST);
            if (request != null && !response.containsHeader(mHeader)) {
                Header header = request.getFirstHeader(mHeader);
                if (header != null) {
                    response.addHeader(mHeader, header.getValue());
                }
            }
        }
    }
}
