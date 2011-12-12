package org.devtcg.rojocam;

import android.text.TextUtils;

import java.util.EnumSet;
import java.util.Iterator;

/**
 * Represents the various camera policies that can be applied to affect camera
 * and streaming behaviours. Where appropriate, multiple policies may be
 * combined using EnumSet.
 */
public enum CameraPolicy {
    /**
     * Activates various features designed to warn potential subjects within
     * view of the camera before the stream is fully opened. Today, a loud sound
     * will be played, the camera LED flash will toggle on and off for 3
     * seconds, and then finally the stream is opened. While this is happening,
     * the streaming client will see a static "please wait" image indicating
     * that the camera is being opened.
     */
    POLICY_SUBJECT_WARNING;

    public static final CameraPolicy DEFAULT_POLICY = POLICY_SUBJECT_WARNING;

    public static EnumSet<CameraPolicy> fromString(String policiesString) {
        EnumSet<CameraPolicy> policies = EnumSet.noneOf(CameraPolicy.class);
        for (String policyString: policiesString.split("\\|")) {
            if (!TextUtils.isEmpty(policyString)) {
                CameraPolicy policy = CameraPolicy.valueOf(policyString);
                policies.add(policy);
            }
        }
        return policies;
    }

    public static String toString(EnumSet<CameraPolicy> policies) {
        StringBuilder b = new StringBuilder();
        Iterator<CameraPolicy> iter = policies.iterator();
        if (iter.hasNext()) {
            b.append(iter.next().toString());
            while (iter.hasNext()) {
                b.append('|');
                b.append(iter.next().toString());
            }
        }
        return b.toString();
    }
}
