package org.devtcg.rojocam.rtsp;

import java.io.IOException;

/**
 * This interface is to be implemented to concretely bind RTSP methods to an
 * RTP/RTCP stream.
 */
public interface MediaSession {
    public RtpTransport onSetup(String feedUri) throws IOException;
    public void onPlay(String feedUri);
    public void onPause(String feedUri);
    public void onTeardown(String feedUri);
}
