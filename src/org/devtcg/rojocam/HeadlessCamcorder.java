package org.devtcg.rojocam;

import org.devtcg.rojocam.util.CameraParametersCompat;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Abstraction of the MediaRecorder APIs to provide a simpler interface and
 * allow for headless recording (the SurfaceView is constructed as part of a
 * system dialog and directly add to the WindowManager).
 * <p>
 * This class is designed to be discarded after the camcorder is stopped. A new
 * instance should be created for each recording session.
 * <p>
 * Today we ask that this class be thread-safe around certain conditions but we
 * do not enforce that. Bugs will likely exist when we introduce multiple peers
 * due to this.
 */
public abstract class HeadlessCamcorder implements SurfaceHolder.Callback {
    private static final String TAG = HeadlessCamcorder.class.getSimpleName();

    private final WeakReference<Context> mContext;

    /**
     * The Camera infrastructure requires a surface for preview when recording
     * video, but in our case we want to appear as if we're recording headlessly
     * without any specific foreground activity. To achieve this effect, we will
     * satisfy this requirement with a dummy 1x1 SurfaceView attached to the
     * window manager.
     */
    private SurfaceView mDummySurfaceView;

    /**
     * This holder is only active when the surface is created and ready to use
     * (so, recording can begin).
     */
    private SurfaceHolder mDummySurfaceHolder;

    private static final int NOT_RECORDING = 0;
    private static final int WAITING_FOR_SURFACE = 1;
    private static final int RECORDING = 2;

    private int mRecordingState = NOT_RECORDING;

    /* Non-null only when in the RECORDING state. */
    private Camera mCamera;

    public HeadlessCamcorder(Context context) {
        mContext = new WeakReference<Context>(context);
    }

    protected Context getContext() {
        Context context = mContext.get();
        if (context == null) {
            throw new AssertionError();
        }
        return context;
    }

    /**
     * Starts the process of recording from the camera. This method blocks.
     */
    public void start() {
        if (isStarted()) {
            throw new IllegalStateException("Camcorder already started");
        }

        /* XXX: NOOOOOO!!!! */
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                mRecordingState = WAITING_FOR_SURFACE;
                makeAndAddSurfaceView();
            }
        });
    }

    /**
     * Stops recording. This method blocks.
     */
    public void stop() {
        if (!isStarted()) {
            throw new IllegalStateException("Camcorder not started");
        }

        if (mRecordingState == RECORDING) {
            /* XXX: stopRecorder will set mRecordingState for us. */
            stopRecorder();
        } else {
            mRecordingState = NOT_RECORDING;
        }
        removeSurfaceView();
    }

    public boolean isStarted() {
        return mRecordingState != NOT_RECORDING;
    }

    /**
     * Override this method to set the recorder parameters (quality, output
     * file, etc) prior to recording.
     */
    protected abstract void onRecorderInitialized(Camera camera);

    /**
     * Invoked prior to the recorder being stopped. Only called after
     * onRecorderInitialized has also been called.
     */
    protected abstract void onRecorderStopped(Camera camera);

    /**
     * Constructs and registers a dummy SurfaceView to be used as the preview
     * surface for the Camera object. We must wait until after the underlying
     * surface is created before we can proceed with the recording process.
     */
    private void makeAndAddSurfaceView() {
        SurfaceView dummyView = new SurfaceView(getContext());
        SurfaceHolder holder = dummyView.getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(100, 100,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.RIGHT;
        params.x = params.y = getContext().getResources().getDimensionPixelOffset(
                R.dimen.preview_surface_offset);
        wm.addView(dummyView, params);

        mDummySurfaceView = dummyView;
    }

    private void removeSurfaceView() {
        WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.removeView(mDummySurfaceView);
        mDummySurfaceView = null;
    }

    private void startRecorder() throws IOException {
        mCamera = Camera.open();
        if (mCamera == null) {
            throw new UnsupportedOperationException("No camera present");
        }

        mCamera.setPreviewDisplay(mDummySurfaceHolder);

        /* Responsible for setting parameters and establishing the preview callbacks. */
        onRecorderInitialized(mCamera);

        Camera.Parameters params = mCamera.getParameters();
        int format = params.getPreviewFormat();
        Size size = params.getPreviewSize();
        int[] frameRates = new int[2];
        CameraParametersCompat.newInstance(params).getPreviewFpsRange(frameRates);
        Log.w(TAG, "Camera properties: bpp=" + ImageFormat.getBitsPerPixel(format) +
                "; format=" + format +
                "; size=" + size.width + "x" + size.height +
                "; frameRates=" + frameRates[0] + "-" + frameRates[1]);

        try {
            mCamera.startPreview(); // Recording is now started
        } catch (RuntimeException e) {
            stopRecorder();
            throw e;
        }

        mRecordingState = RECORDING;
    }

    private void stopRecorder() {
        Log.i(TAG, "Stopping recorder...");

        if (mRecordingState == RECORDING) {
            onRecorderStopped(mCamera);
            mCamera.stopPreview();
        }
        mCamera.release();
        mCamera = null;
        mRecordingState = NOT_RECORDING;
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged: format=" + format + "; width=" + width + "; height=" + height);

        /* XXX: com.android.camera does this check, but why? */
        if (holder.getSurface() == null) {
            Log.d(TAG, "holder.getSurface() is null");
            return;
        }

        mDummySurfaceHolder = holder;

        if (mRecordingState == WAITING_FOR_SURFACE) {
            try {
                /* Start recorder is expected to change the state into RECORDING. */
                Log.i(TAG, "Starting recorder...");
                startRecorder();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated");
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");
        mDummySurfaceHolder = null;
    }
}
