package org.devtcg.rojocam.rtsp;

import org.apache.http.HttpStatus;

public interface RtspStatus extends HttpStatus {
    public static final int SC_SESSION_NOT_FOUND = 454;
    public static final int SC_METHOD_NOT_VALID_IN_THIS_STATE = 455;
    public static final int SC_UNSUPPORTED_TRANSPORT = 461;
}
