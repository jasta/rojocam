package org.devtcg.rojocam;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.util.EnumSet;

/**
 * Responds to camera events with appropriate user-notifying behaviour such as
 * adding and modifying the status bar icon and playing recording sounds.
 */
public class UserAlertHelper {
    private static final String TAG = UserAlertHelper.class.getSimpleName();

    private final Context mContext;

    private final NotificationManager mNotifMgr;
    private CamcorderNodeService.State mCurrentState;

    private static final int NOTIF_ID = 1;

    public UserAlertHelper(Context context) {
        mContext = context.getApplicationContext();
        mNotifMgr = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private Context getContext() {
        return mContext;
    }

    public void changeState(CamcorderNodeService.State state) {
        if (mCurrentState == state) {
            Log.w(TAG, "Redundant state change to " + state + " ignored.");
        } else {
            Log.i(TAG, "Alerting for state " + state);

            switch (state) {
                case ACTIVE:
                case STREAMING:
                    setNotification(state);
                    if (state == CamcorderNodeService.State.STREAMING) {
                        EnumSet<CameraPolicy> policies = SettingsActivity.getPolicy(getContext());

                        if (policies.contains(CameraPolicy.POLICY_SUBJECT_WARNING)) {
                            /*
                             * Play an alert sound and although we don't control the
                             * code here directly, we'll also flash the Camera LED
                             * to warn the recipient that they are about to be
                             * recorded.
                             */
                            playAlertSound();
                        }
                    }
                    break;
                case DEACTIVE:
                    removeNotification();
                    break;
            }

            mCurrentState = state;
        }
    }

    private void setNotification(CamcorderNodeService.State state) {
        boolean isStreaming = state == CamcorderNodeService.State.STREAMING;

        Intent intent = new Intent(getContext(), ControllerActivity.class);

        int notifTitle;
        int notifText;
        int iconId;
        if (isStreaming) {
            notifTitle = R.string.recording_notif_title;
            notifText = R.string.recording_notif_text;
            iconId = android.R.drawable.stat_sys_phone_call;
        } else {
            notifTitle = R.string.running_notif_title;
            notifText = R.string.running_notif_text;
            iconId = android.R.drawable.stat_sys_upload;
        }

        Notification notif = new Notification();
        notif.icon = iconId;
        notif.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        notif.setLatestEventInfo(getContext(), getContext().getString(notifTitle),
                getContext().getString(notifText),
                PendingIntent.getActivity(getContext(), 0, intent, 0));

        mNotifMgr.notify(NOTIF_ID, notif);
    }

    private void playAlertSound() {
        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        try {
            ImportantSoundPlayer.play(getContext(), soundUri);
        } catch (IOException e) {
            Log.e(TAG, "Failed to play alert sound");
        }
    }

    private void removeNotification() {
        mNotifMgr.cancel(NOTIF_ID);
    }

    private static class ImportantSoundPlayer implements OnCompletionListener {
        private final AudioManager mAudioMgr;
        private final MediaPlayer mPlayer;

        public static void play(Context context, Uri soundUri) throws IOException {
            ImportantSoundPlayer player = new ImportantSoundPlayer(context, soundUri);
            player.play();
        }

        private ImportantSoundPlayer(Context context, Uri soundUri) throws IOException {
            mPlayer = new MediaPlayer();
            mPlayer.setOnCompletionListener(this);
            mPlayer.setDataSource(context, soundUri);
            mAudioMgr = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        }

        public void play() throws IOException {
            /* XXX: Should I force this to the max volume before playing? */
            if (mAudioMgr.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
                mPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
                mPlayer.prepare();
                mPlayer.start();
            } else {
                Log.w(TAG, "Not playing alerting sound due to volume configuration");
            }
        }

        @Override
        public void onCompletion(MediaPlayer mp) {
            mp.release();
        }
    }

    /**
     * Object controlling the logic rules for how the LED camera flash is to be
     * used for user alert purposes, even though the code for factoring reasons
     * is going to live with the StreamingHeadlessCamcorder class.
     */
    public static class SubjectWarning {
        /**
         * Total length of time we will be fiddling with the camera flash.
         * Camera images should not be sent during this time (to give the camera
         * subject time to react).
         */
        private static final int TOTAL_DURATION = 3000;

        /**
         * Interval at which we turn the LED on and off. This rule causes us to
         * begin with the LED on for 1 second, then off for 1 second, and on for
         * another second. After this period, the camera will be turned on.
         */
        private static final int ON_OFF_INTERVAL = 1000;

        private final long mStartTime = System.currentTimeMillis();

        public boolean isWarningPeriodActive() {
            long elapsed = System.currentTimeMillis() - mStartTime;
            return elapsed <= TOTAL_DURATION;
        }

        public boolean getLedState() {
            long elapsed = System.currentTimeMillis() - mStartTime;
            return (elapsed % (ON_OFF_INTERVAL * 2)) < ON_OFF_INTERVAL;
        }
    }
}
