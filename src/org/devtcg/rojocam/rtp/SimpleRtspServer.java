
package org.devtcg.rojocam.rtp;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.StringEntity;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.devtcg.rojocam.rtp.RtspSession.RtspState;

import android.util.Log;

import java.io.IOException;
import java.util.HashMap;

public class SimpleRtspServer extends AbstractRtspServer implements HttpRequestHandler {
    private static final String TAG = SimpleRtspServer.class.getSimpleName();

    private final HashMap<String, HttpRequestHandler> mMethodHandlers;

    /* XXX: Sessions are not expired on a timer, only by graceful TEARDOWN. */
    private final HashMap<String, RtspSession> mSessions = new HashMap<String, RtspSession>();

    static {
    }

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

    private static class DescribeHandler implements HttpRequestHandler {
        private static final String SDP_CONTENT_TYPE = "application/sdp";

        public void handle(HttpRequest request, HttpResponse response, HttpContext context)
                throws HttpException, IOException {
            Header acceptHeader = request.getFirstHeader(RtspHeaders.ACCEPT);
            if (acceptHeader != null && acceptHeader.getValue().equals(SDP_CONTENT_TYPE)) {
                response.addHeader(RtspHeaders.CONTENT_BASE, request.getRequestLine().getUri()
                        + "/");
                StringEntity entity = new StringEntity(buildSdp());
                entity.setContentType(SDP_CONTENT_TYPE);
                response.setEntity(entity);
                response.setStatusCode(HttpStatus.SC_OK);
            } else {
                Log.d(TAG,
                        "Client is asking for something other than application/sdp in DESCRIBE request, *confused*");
                response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
            }
        }

        /*
         * XXX: I haven't bothered to understand this format well enough to
         * create a proper builder class.
         */
        private static String buildSdp() {
            StringBuilder b = new StringBuilder();
            b.append("o=- 0 0 IN IP4 127.0.0.1\n");
            b.append("t=0 0\n");
            b.append("s=No Title\n");
            b.append("m=video 0 RTP/AVP 96\n");
            b.append("a=rtpmap:96 H263-1998/90000\n");
            b.append("a=control:streamid=0\n");
            b.append("a=framesize:96 176-144\n");
            return b.toString();
        }
    }

    private class SetupHandler implements HttpRequestHandler {
        public void handle(HttpRequest request, HttpResponse response, HttpContext context)
                throws HttpException, IOException {
            Header transportHeader = request.getFirstHeader(RtspHeaders.TRANSPORT);
            if (transportHeader != null) {
                RtpTransportDesc transport = RtpTransportDesc.fromString(transportHeader.getValue());
                if (transport.lowerTransport == RtpTransportDesc.Transport.UDP &&
                        transport.clientRtpPort != 0 &&
                        transport.destType == RtpTransportDesc.DestinationType.UNICAST) {
                    transport.serverRtpPort = 5000;
                    transport.serverRtcpPort = 5001;
                    RtspSession session = beginSession();
                    response.addHeader(RtspHeaders.SESSION, session.getSessionId());
                    response.addHeader(RtspHeaders.TRANSPORT, transport.toString());
                    response.setStatusCode(HttpStatus.SC_OK);
                } else {
                    Log.d(TAG, "Client requested unsupported transport: " + transportHeader.getValue());
                    response.setStatusCode(RtspStatus.SC_UNSUPPORTED_TRANSPORT);
                }
            } else {
                Log.d(TAG, "Client missing Transport header");
                response.setStatusCode(RtspStatus.SC_UNSUPPORTED_TRANSPORT);
            }
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
                Log.d(TAG, "Supposed to start playing, but we don't have an RTP implementation yet!");
                session.setState(RtspState.PLAYING);
            }
            response.setStatusCode(HttpStatus.SC_OK);
        }
    }

    private class PauseHandler extends InSessionHandler {
        public void handle(HttpRequest request, HttpResponse response, HttpContext context, RtspSession session)
                throws HttpException, IOException {
            if (session.getState() == RtspState.PLAYING) {
                Log.d(TAG, "Supposed to pause, but we don't have any RTP socket to close!");
                session.setState(RtspState.READY);
            }
            response.setStatusCode(HttpStatus.SC_OK);
        }
    }

    private class TeardownHandler extends InSessionHandler {
        public void handle(HttpRequest request, HttpResponse response, HttpContext context, RtspSession session)
                throws HttpException, IOException {
            endSession(session);
        }
    }

    private static class RtpTransportDesc {
        public enum Transport {
            TCP, UDP,
        }

        public enum DestinationType {
            MULTICAST, UNICAST,
        }

        public Transport lowerTransport;
        public DestinationType destType;
        public int clientRtpPort;
        public int clientRtcpPort;
        public int serverRtpPort;
        public int serverRtcpPort;

        private RtpTransportDesc() {
        }

        public static RtpTransportDesc fromString(String string) throws HttpException {
            RtpTransportDesc desc = new RtpTransportDesc();
            for (String segment : string.split(";")) {
                if (segment.startsWith("RTP/")) {
                    for (String transportPart: segment.split("/", 3)) {
                        if (transportPart.equals("TCP")) {
                            desc.lowerTransport = Transport.TCP;
                        } else {
                            desc.lowerTransport = Transport.UDP;
                        }
                    }
                    if (desc.lowerTransport == null) {
                        Log.d(TAG, "Unable to determine lower transport: " + segment);
                    }
                } else if (segment.startsWith("client_port=")) {
                    String[] ports = segment.substring(12).split("-", 2);
                    try {
                        desc.clientRtpPort = Integer.parseInt(ports[0]);
                        if (ports.length > 1) {
                            desc.clientRtcpPort = Integer.parseInt(ports[1]);
                        }
                    } catch (NumberFormatException e) {
                    }
                    if (desc.clientRtpPort == 0) {
                        Log.d(TAG, "Unparseable client ports: " + segment);
                    }
                } else {
                    if (segment.equals("unicast")) {
                        desc.destType = DestinationType.UNICAST;
                    } else if (segment.equals("multicast")) {
                        desc.destType = DestinationType.MULTICAST;
                    } else {
                        Log.d(TAG, "Unrecognized transport option: " + segment);
                    }
                }
            }
            return desc;
        }

        public String toString() {
            if (lowerTransport != Transport.UDP) {
                throw new UnsupportedOperationException("We only support UDP transports");
            }
            StringBuilder b = new StringBuilder();
            b.append("RTP/AVP/" + lowerTransport);
            b.append(';');
            if (destType != DestinationType.UNICAST) {
                throw new UnsupportedOperationException("We only support unicast packets");
            }
            b.append("unicast");
            b.append(';');
            b.append("client_port=" + clientRtpPort + "-" + clientRtcpPort);
            if (serverRtpPort != 0 && serverRtcpPort != 0) {
                b.append(';');
                b.append("server_port=" + serverRtpPort + "-" + serverRtcpPort);
            }
            return b.toString();
        }
    }
}
