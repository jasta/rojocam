package org.devtcg.rojocam.rtsp;

import java.net.InetAddress;

public interface MediaHandler {
    public String onDescribe(String feedUri);
    public MediaSession createSession(InetAddress client, RtpTransport transport);
}
