package org.devtcg.rojocam.rtsp;

public class RtpParticipant {
    public final String hostAddress;
    public final int rtpPort;
    public final int rtcpPort;

    public RtpParticipant(String hostAddress, int rtpPort, int rtcpPort) {
        this.hostAddress = hostAddress;
        this.rtpPort = rtpPort;
        this.rtcpPort = rtcpPort;
    }

    @Override
    public int hashCode() {
        return hostAddress.hashCode() + rtpPort + rtcpPort;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append('{');
        b.append("host=").append(hostAddress).append(';');
        b.append("rtpPort=").append(rtpPort).append(';');
        b.append("rtcpPort=").append(rtcpPort);
        b.append('}');
        return b.toString();
    }


}