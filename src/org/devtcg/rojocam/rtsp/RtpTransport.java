package org.devtcg.rojocam.rtsp;

import java.text.ParseException;

/**
 * Crudely represents an RTP transport as specified by RTSP.
 */
public class RtpTransport {
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

    private RtpTransport() {
    }

    public RtpTransport(RtpTransport t) {
        lowerTransport = t.lowerTransport;
        destType = t.destType;
        clientRtpPort = t.clientRtpPort;
        clientRtcpPort = t.clientRtcpPort;
        serverRtpPort = t.serverRtpPort;
        serverRtcpPort = t.serverRtcpPort;
    }

    public static RtpTransport fromString(String string) throws ParseException {
        RtpTransport desc = new RtpTransport();
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
                    throw new ParseException("Unable to determine lower transport: " + segment, 0);
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
                    throw new ParseException("Unparseable client ports: " + segment, 0);
                }
            } else {
                if (segment.equals("unicast")) {
                    desc.destType = DestinationType.UNICAST;
                } else if (segment.equals("multicast")) {
                    desc.destType = DestinationType.MULTICAST;
                } else {
                    throw new ParseException("Unrecognized lower transport: " + segment, 0);
                }
            }
        }
        return desc;
    }

    /**
     * Returns a string suitable for placement in the Transport header in an
     * RTSP response.
     */
    public String getHeaderString() {
        return toString();
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
