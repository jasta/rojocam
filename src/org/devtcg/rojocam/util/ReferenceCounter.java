package org.devtcg.rojocam.util;

import android.util.Log;

public abstract class ReferenceCounter<T> {
    private static final String TAG = ReferenceCounter.class.getSimpleName();

    private int mCount;
    private T mInstance;

    public synchronized T acquire() {
        if (mCount == 0) {
            mInstance = onCreate();
        }
        mCount++;
        return mInstance;
    }

    public synchronized void release() {
        if (mCount < 1) {
            throw new IllegalStateException("Unbalanced release calls");
        }
        mCount--;
        if (mCount == 0) {
            onDestroy(mInstance);
            mInstance = null;
        }
    }

    public synchronized boolean isAcquired() {
        return mCount > 0;
    }

    protected abstract T onCreate();
    protected abstract void onDestroy(T instance);

    @Override
    protected void finalize() throws Throwable {
        if (mCount > 0) {
            Log.w(TAG, "Finalizer called on unbalanced counter for object of type=" +
                    mInstance.getClass().getName());
        }
    }
}
