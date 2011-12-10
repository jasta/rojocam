
package org.devtcg.rojocam.rtsp;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolException;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.SocketHttpServerConnection;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.devtcg.rojocam.rtsp.RtspSession.RtspState;

import android.util.Log;

import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;

/**
 * Very crude RTSP implementation designed only to support the bare minimum
 * required for the camcorder node.
 */
public class SimpleRtspServer extends AbstractRtspServer implements HttpRequestHandler {
    private static final String TAG = SimpleRtspServer.class.getSimpleName();

    private final HashMap<String, HttpRequestHandler> mMethodHandlers;

    /* XXX: Sessions are not expired on a timer, only by graceful TEARDOWN. */
    private final HashMap<String, RtspSession> mSessions = new HashMap<String, RtspSession>();

    /*
     * XXX: Weak abstraction attempting to map RTSP request URIs with some
     * high-level handler interface that can be implemented outside this class
     * to coordinate the RTP/RTCP streams.
     */
    private MediaHandler mMediaHandler;

    public SimpleRtspServer() {
        /* Catch-all request handler. */
        setRequestHandler(this);

        /* Method-specific handlers. */
        mMethodHandlers = new HashMap<String, HttpRequestHandler>();
        mMethodHandlers.put(RtspMethods.OPTIONS, new OptionsHandler());
        mMethodHandlers.put(RtspMethods.DESCRIBE, new DescribeHandler());
        mMethodHandlers.put(RtspMethods.SETUP, new SetupHandler());
        mMethodHandlers.put(RtspMethods.PLAY, new PlayHandler());
        mMethodHandlers.put(RtspMethods.PAUSE, new PauseHandler());
        mMethodHandlers.put(RtspMethods.TEARDOWN, new TeardownHandler());
    }

    @Override
    protected void onPreShutdown() {
        synchronized (mSessions) {
            for (RtspSession session: mSessions.values()) {
                Log.i(TAG, "Force terminating session " + session.getSessionId());
                session.getMediaSession().onTeardown(null);
            }
            mSessions.clear();
        }
    }

    public synchronized void registerMedia(String feedUri, MediaHandler handler) {
        mMediaHandler = handler;
    }

    public synchronized void unregisterMedia(String feedUri) {
        mMediaHandler = null;
    }

    private synchronized MediaHandler getHandler() {
        return mMediaHandler;
    }

    private RtspSession beginSession() {
        RtspSession session = new RtspSession(RtspState.READY);
        synchronized (mSessions) {
            mSessions.put(session.getSessionId(), session);
        }
        return session;
    }

    private RtspSession getSession(HttpRequest request) {
        Header sessionHeader = request.getFirstHeader(RtspHeaders.SESSION);
        if (sessionHeader == null) {
            return null;
        } else {
            synchronized (mSessions) {
                return mSessions.get(sessionHeader.getValue());
            }
        }
    }

    private void endSession(RtspSession session) {
        synchronized (mSessions) {
            mSessions.remove(session.getSessionId());
        }
    }

    public void handle(HttpRequest request, HttpResponse response, HttpContext context)
            throws HttpException, IOException {
        System.out.println("Got request: " + request.getRequestLine());
        String method = request.getRequestLine().getMethod();

        HttpRequestHandler handler = mMethodHandlers.get(method);
        if (handler != null) {
            handler.handle(request, response, context);
        } else {
            response.setStatusCode(HttpStatus.SC_NOT_IMPLEMENTED);
        }
    }

    private static class OptionsHandler implements HttpRequestHandler {
        public void handle(HttpRequest request, HttpResponse response, HttpContext context)
                throws HttpException, IOException {
            response.addHeader(
                    RtspHeaders.PUBLIC,
                    StringUtils.join(new String[] {
                            RtspMethods.OPTIONS, RtspMethods.DESCRIBE, RtspMethods.SETUP,
                            RtspMethods.TEARDOWN, RtspMethods.PLAY, RtspMethods.PAUSE
                    }, ", "));
            response.setStatusCode(HttpStatus.SC_OK);
        }
    }

    private class DescribeHandler implements HttpRequestHandler {
        private static final String SDP_CONTENT_TYPE = "application/sdp";

        public void handle(HttpRequest request, HttpResponse response, HttpContext context)
                throws HttpException, IOException {
            Header acceptHeader = request.getFirstHeader(RtspHeaders.ACCEPT);
            if (acceptHeader != null && acceptHeader.getValue().equals(SDP_CONTENT_TYPE)) {
                response.addHeader(RtspHeaders.CONTENT_BASE, request.getRequestLine().getUri()
                        + "/");
                StringEntity entity = new StringEntity(getHandler().onDescribe(null));
                entity.setContentType(SDP_CONTENT_TYPE);
                response.setEntity(entity);
                response.setStatusCode(HttpStatus.SC_OK);
            } else {
                Log.d(TAG,
                        "Client is asking for something other than application/sdp in DESCRIBE request, *confused*");
                response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
            }
        }
    }

    private class SetupHandler implements HttpRequestHandler {
        public void handle(HttpRequest request, HttpResponse response, HttpContext context)
                throws HttpException, IOException {
            Header transportHeader = request.getFirstHeader(RtspHeaders.TRANSPORT);
            if (transportHeader == null) {
                throw new ProtocolException("Missing transport header");
            }
            try {
                RtpTransport transport = RtpTransport.fromString(transportHeader.getValue());
                SocketHttpServerConnection conn = (SocketHttpServerConnection)context.getAttribute(
                        ExecutionContext.HTTP_CONNECTION);
                if (transport.lowerTransport == RtpTransport.Transport.UDP &&
                        transport.clientRtpPort != 0 &&
                        transport.destType == RtpTransport.DestinationType.UNICAST) {
                    RtspSession session = beginSession();
                    session.setMediaSession(getHandler().createSession(conn.getRemoteAddress(), transport));
                    RtpTransport serverTransport = session.getMediaSession().onSetup(null);
                    response.addHeader(RtspHeaders.SESSION, session.getSessionId());
                    response.addHeader(RtspHeaders.TRANSPORT, serverTransport.toString());
                    response.setStatusCode(HttpStatus.SC_OK);
                }
                return;
            } catch (ParseException e) {
            }
            Log.d(TAG, "Client requested unsupported transport: " + transportHeader.getValue());
            response.setStatusCode(RtspStatus.SC_UNSUPPORTED_TRANSPORT);
        }
    }

    private abstract class InSessionHandler implements HttpRequestHandler {
        public void handle(HttpRequest request, HttpResponse response, HttpContext context)
                throws HttpException, IOException {
            RtspSession session = getSession(request);
            if (session != null) {
                handle(request, response, context, session);
            } else {
                response.setStatusCode(RtspStatus.SC_SESSION_NOT_FOUND);
            }
        }

        protected abstract void handle(HttpRequest request, HttpResponse response, HttpContext context,
                RtspSession session) throws HttpException, IOException;
    }

    private class PlayHandler extends InSessionHandler {
        public void handle(HttpRequest request, HttpResponse response, HttpContext context, RtspSession session)
                throws HttpException, IOException {
            if (session.getState() != RtspState.PLAYING) {
                session.setState(RtspState.PLAYING);
                session.getMediaSession().onPlay(null);
            }
            response.setStatusCode(HttpStatus.SC_OK);
        }
    }

    private class PauseHandler extends InSessionHandler {
        public void handle(HttpRequest request, HttpResponse response, HttpContext context, RtspSession session)
                throws HttpException, IOException {
            if (session.getState() == RtspState.PLAYING) {
                session.setState(RtspState.READY);
                session.getMediaSession().onPause(null);
            }
            response.setStatusCode(HttpStatus.SC_OK);
        }
    }

    private class TeardownHandler extends InSessionHandler {
        public void handle(HttpRequest request, HttpResponse response, HttpContext context, RtspSession session)
                throws HttpException, IOException {
            session.getMediaSession().onTeardown(null);
            endSession(session);
        }
    }
}
