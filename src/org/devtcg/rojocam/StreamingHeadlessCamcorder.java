package org.devtcg.rojocam;

import org.devtcg.rojocam.ffmpeg.RtpOutputContext;
import org.devtcg.rojocam.util.IOUtils;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

public class StreamingHeadlessCamcorder extends HeadlessCamcorder {
    private static final String TAG = StreamingHeadlessCamcorder.class.getSimpleName();

    private int mPreviewFormat;
    private Size mPreviewSize;
    private int mPreviewBitsPerPixel;

    private final CopyOnWriteArraySet<RtpOutputContext> mReceivers =
            new CopyOnWriteArraySet<RtpOutputContext>();

    public StreamingHeadlessCamcorder(Context context) throws SocketException {
        super(context);
    }

    /**
     * Add a new peer that is to receive the camera feed.
     */
    public void addReceiver(RtpOutputContext rtpContext) {
        mReceivers.add(rtpContext);
    }

    /**
     * Remove a peer from this feed. The camera remains open when all
     * participants are removed but no data will be delivered to any parties.
     */
    public void removeReceiver(RtpOutputContext rtpContext) {
        mReceivers.remove(rtpContext);
    }

    @Override
    protected void onRecorderInitialized(Camera camera) {
        Camera.Parameters params = camera.getParameters();

        List<Integer> formats = params.getSupportedPreviewFormats();
        Log.d(TAG, "Supported formats:");
        for (Integer format: formats) {
            Log.d(TAG, "  format=" + format);
        }

        List<Size> sizes = params.getSupportedPreviewSizes();
        Log.d(TAG, "Supported sizes:");
        for (Size size: sizes) {
            Log.d(TAG, "  size=" + size.width + "x" + size.height);
        }

        /*
         * The documentation says NV21 is for image, and NV17 is for video, but
         * it looks like folks are using NV21 for video recording as well...
         */
        params.setPreviewFormat(ImageFormat.NV21);

        camera.setParameters(params);

        mPreviewFormat = params.getPreviewFormat();
        mPreviewSize = params.getPreviewSize();
        mPreviewBitsPerPixel = ImageFormat.getBitsPerPixel(mPreviewFormat);

        camera.setPreviewCallbackWithBuffer(mPreviewCallback);

        Size size = mPreviewSize;
        float bytesPerPixel = mPreviewBitsPerPixel / 8f;
        int bufSize = (int)(size.width * size.height * bytesPerPixel);
        byte[] buf = new byte[bufSize];
        camera.addCallbackBuffer(buf);
    }

    @Override
    protected void onRecorderStopped(Camera recorder) {
        for (RtpOutputContext rtpContext: mReceivers) {
            IOUtils.closeQuietly(rtpContext);
        }
    }

    private final PreviewCallback mPreviewCallback = new PreviewCallback() {
        public void onPreviewFrame(byte[] data, Camera camera) {
            /*
             * XXX: We're attempting to send out the encoded frames to all
             * participants as fast as they come in but obviously this doesn't
             * scale at all. We need to process each participant in its own
             * thread, using a sort of frame ringbuffer to keep the preview
             * frames going.
             */
            long now = System.nanoTime();
            ArrayList<RtpOutputContext> toRemove = null;
            for (RtpOutputContext rtpContext: mReceivers) {
                try {
                    Log.w(TAG, "writeFrame: now=" + now);
                    rtpContext.writeFrame(data, now,
                            mPreviewFormat, mPreviewSize, mPreviewBitsPerPixel);
                } catch (IOException e) {
                    Log.w(TAG, "Error writing to RTP participant: " + rtpContext.getPeer());
                    IOUtils.closeQuietly(rtpContext);
                    if (toRemove != null) {
                        toRemove = new ArrayList<RtpOutputContext>();
                    }
                    toRemove.add(rtpContext);
                }
            }

            if (toRemove != null) {
                for (int i = 0; i < toRemove.size(); i++) {
                    mReceivers.remove(toRemove.get(i));
                }
            }

            camera.addCallbackBuffer(data);
        }
    };
}

