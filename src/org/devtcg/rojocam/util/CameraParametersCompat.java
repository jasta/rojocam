package org.devtcg.rojocam.util;

import android.hardware.Camera.Parameters;
import android.os.Build;

public abstract class CameraParametersCompat {
    protected Parameters mParams;

    protected CameraParametersCompat(Parameters params) {
        mParams = params;
    }

    public static CameraParametersCompat newInstance(Parameters params) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return new GingerbreadAndBeyond(params);
        } else {
            return new PreGingerbread(params);
        }
    }

    public abstract void getPreviewFpsRange(int[] range);

    private static class GingerbreadAndBeyond extends CameraParametersCompat {
        public GingerbreadAndBeyond(Parameters params) {
            super(params);
        }

        @Override
        public void getPreviewFpsRange(int[] range) {
            mParams.getPreviewFpsRange(range);
        }
    }

    private static class PreGingerbread extends CameraParametersCompat {
        public PreGingerbread(Parameters params) {
            super(params);
        }

        @Override
        public void getPreviewFpsRange(int[] range) {
            int fps = mParams.getPreviewFrameRate();
            range[0] = range[1] = fps;
        }
    }
}
