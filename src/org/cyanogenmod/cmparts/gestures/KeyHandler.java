/**
 * Copyright (C) 2016 The CyanogenMod project
 *               2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cyanogenmod.cmparts.gestures;

import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.Manifest;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

import cyanogenmod.providers.CMSettings;

import java.util.List;

public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = KeyHandler.class.getSimpleName();

    private static final String GESTURE_WAKEUP_REASON = "cmparts-gesture-wakeup";
    private static final String GESTURE_ACTION_KEY = "touchscreen_gesture_action_key";
    private static final int GESTURE_REQUEST = 0;
    private static final int GESTURE_WAKELOCK_DURATION = 3000;
    private static final int EVENT_PROCESS_WAKELOCK_DURATION = 500;

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final WakeLock mGestureWakeLock;
    private final EventHandler mEventHandler;
    private final CameraManager mCameraManager;
    private final Vibrator mVibrator;

    private final SparseArray mActionMapping = new SparseArray();
    private final boolean mProximityWakeSupported;
    private SensorManager mSensorManager;
    private Sensor mProximitySensor;
    private WakeLock mProximityWakeLock;
    private boolean mDefaultProximity;
    private int mProximityTimeOut;

    private KeyguardManager mKeyguardManager;
    private KeyguardLock mKeyguardLock;
    private boolean disableKGbyScreenOn;
    private boolean isKGDismissed;

    private String mRearCameraId;
    private boolean mTorchEnabled;

    private final BroadcastReceiver mUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(TouchscreenGestureConstants.UPDATE_PREFS_ACTION)) {
                int[] keycodes = intent.getIntArrayExtra(
                        TouchscreenGestureConstants.UPDATE_EXTRA_KEYCODE_MAPPING);
                String[] actions = intent.getStringArrayExtra(
                        TouchscreenGestureConstants.UPDATE_EXTRA_ACTION_MAPPING);
                mActionMapping.clear();
                if (keycodes != null && actions != null && keycodes.length == actions.length) {
                    for (int i = 0; i < keycodes.length; i++) {
                        mActionMapping.put(keycodes[i], actions[i]);
                    }
                }
            }
            else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                Log.d(TAG, "Screen turned on");
                if(disableKGbyScreenOn) {
                    Log.d(TAG, "disabling keyguard");
                    ensureKeyguardManager();
                    if (!mKeyguardManager.isKeyguardSecure() && !isKGDismissed) {
                        mKeyguardLock.disableKeyguard();
                        isKGDismissed = true;
                        Log.d(TAG, "Disable kg = false");
                        disableKGbyScreenOn = false;
                    }
                }
            }
            else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                Log.d(TAG, "Screen turned off");
                disableKGbyScreenOn = false;
                if(isKGDismissed) {
                    ensureKeyguardManager();
                    if (!mKeyguardManager.isKeyguardSecure()) {
                        Log.d(TAG, "reenable Keyguard");
                        mKeyguardLock.reenableKeyguard();
                        isKGDismissed = false;
                    }
                }
            }
        }
    };

    public KeyHandler(final Context context) {
        mContext = context;

        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mGestureWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "CMPartsGestureWakeLock");

        mEventHandler = new EventHandler();

        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        mCameraManager.registerTorchCallback(new TorchModeCallback(), mEventHandler);

        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        final Resources resources = mContext.getResources();
        mProximityWakeSupported = resources.getBoolean(
                org.cyanogenmod.platform.internal.R.bool.config_proximityCheckOnWake);

        if (mProximityWakeSupported) {
            mProximityTimeOut = resources.getInteger(
                    org.cyanogenmod.platform.internal.R.integer.config_proximityCheckTimeout);
            mDefaultProximity = mContext.getResources().getBoolean(
                    org.cyanogenmod.platform.internal.R.bool.config_proximityCheckOnWakeEnabledByDefault);

            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            mProximityWakeLock = mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "CMPartsProximityWakeLock");
        }
        IntentFilter filter = new IntentFilter(TouchscreenGestureConstants.UPDATE_PREFS_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(mUpdateReceiver, filter);
    }

    private class TorchModeCallback extends CameraManager.TorchCallback {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (!cameraId.equals(mRearCameraId)) return;
            mTorchEnabled = enabled;
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (!cameraId.equals(mRearCameraId)) return;
            mTorchEnabled = false;
        }
    }

    private void ensureKeyguardManager() {
        if (mKeyguardManager == null) {
            mKeyguardManager =
                    (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        }
        if (mKeyguardLock == null) {
            mKeyguardLock = mKeyguardManager.newKeyguardLock(Context.KEYGUARD_SERVICE);
        }
    }

    public boolean handleKeyEvent(final KeyEvent event) {
        final String action = mActionMapping.get(event.getScanCode(), "").toString();
        if (action.equals("") || event.getAction() != KeyEvent.ACTION_UP || !hasSetupCompleted()) {
            return false;
        }

        if (!action.equals("0") && !mEventHandler.hasMessages(GESTURE_REQUEST)) {
            final Message msg = getMessageForAction(action);
            final boolean proxWakeEnabled = CMSettings.System.getInt(mContext.getContentResolver(),
                    CMSettings.System.PROXIMITY_ON_WAKE, mDefaultProximity ? 1 : 0) == 1;
            if (mProximityWakeSupported && proxWakeEnabled && mProximitySensor != null) {
                mGestureWakeLock.acquire(2 * mProximityTimeOut);
                mEventHandler.sendMessageDelayed(msg, mProximityTimeOut);
                processEvent(action);
            } else {
                mGestureWakeLock.acquire(EVENT_PROCESS_WAKELOCK_DURATION);
                mEventHandler.sendMessage(msg);
            }
        }

        return true;
    }

    private boolean hasSetupCompleted() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
    }

    private void processEvent(final String action) {
        mProximityWakeLock.acquire();
        mSensorManager.registerListener(new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                mProximityWakeLock.release();
                mSensorManager.unregisterListener(this);
                if (!mEventHandler.hasMessages(GESTURE_REQUEST)) {
                    // The sensor took too long; ignoring
                    return;
                }
                mEventHandler.removeMessages(GESTURE_REQUEST);
                if (event.values[0] == mProximitySensor.getMaximumRange()) {
                    Message msg = getMessageForAction(action);
                    mEventHandler.sendMessage(msg);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Ignore
            }

        }, mProximitySensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    private Message getMessageForAction(final String action) {
        Message msg = mEventHandler.obtainMessage(GESTURE_REQUEST);
        Bundle b = new Bundle();
        b.putString(GESTURE_ACTION_KEY, action);
        msg.setData(b);
        return msg;
    }

    private class EventHandler extends Handler {
        @Override
        public void handleMessage(final Message msg) {
            Bundle b = msg.getData();
            final String action = b.getString(GESTURE_ACTION_KEY);
            int actionInt = 0;
            try {
                actionInt = Integer.parseInt(String.valueOf(action));
            } catch (NumberFormatException e){
                actionInt = 0;
            }
            switch (actionInt) {
                case 0:
                    tryLaunchCustom(action);
                    break;
                case TouchscreenGestureConstants.ACTION_CAMERA:
                    launchCamera();
                    break;
                case TouchscreenGestureConstants.ACTION_FLASHLIGHT:
                    toggleFlashlight();
                    break;
                case TouchscreenGestureConstants.ACTION_BROWSER:
                    launchBrowser();
                    break;
                case TouchscreenGestureConstants.ACTION_DIALER:
                    launchDialer();
                    break;
                case TouchscreenGestureConstants.ACTION_EMAIL:
                    launchEmail();
                    break;
                case TouchscreenGestureConstants.ACTION_MESSAGES:
                    launchMessages();
                    break;
                case TouchscreenGestureConstants.ACTION_PLAY_PAUSE_MUSIC:
                    playPauseMusic();
                    break;
                case TouchscreenGestureConstants.ACTION_PREVIOUS_TRACK:
                    previousTrack();
                    break;
                case TouchscreenGestureConstants.ACTION_NEXT_TRACK:
                    nextTrack();
                    break;
            }
        }
    }

    private void launchCamera() {
        mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
        final Intent intent = new Intent(cyanogenmod.content.Intent.ACTION_SCREEN_CAMERA_GESTURE);
        mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT,
                Manifest.permission.STATUS_BAR_SERVICE);
        doHapticFeedback();
    }

    private void launchBrowser() {
        Log.d(TAG, "Disable kg = true");
        disableKGbyScreenOn = true;
        mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), GESTURE_WAKEUP_REASON);
        final Intent intent = getLaunchableIntent(
                new Intent(Intent.ACTION_VIEW, Uri.parse("http:")));
        startActivitySafely(intent);
        doHapticFeedback();
    }

    private void launchDialer() {
        Log.d(TAG, "Disable kg = true");
        disableKGbyScreenOn = true;
        mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), GESTURE_WAKEUP_REASON);
        final Intent intent = new Intent(Intent.ACTION_DIAL, null);
        startActivitySafely(intent);
        doHapticFeedback();
    }

    private void launchEmail() {
        Log.d(TAG, "Disable kg = true");
        disableKGbyScreenOn = true;
        mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), GESTURE_WAKEUP_REASON);
        final Intent intent = getLaunchableIntent(
                new Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")));
        startActivitySafely(intent);
        doHapticFeedback();
    }

    private void launchMessages() {
        Log.d(TAG, "Disable kg = true");
        disableKGbyScreenOn = true;
        mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), GESTURE_WAKEUP_REASON);
        final String defaultApplication = Settings.Secure.getString(
                mContext.getContentResolver(), "sms_default_application");
        final PackageManager pm = mContext.getPackageManager();
        final Intent intent = pm.getLaunchIntentForPackage(defaultApplication);
        if (intent != null) {
            startActivitySafely(intent);
        }
        doHapticFeedback(); //Always do haptic feedback if you also wake screen by gesture
    }

    private void toggleFlashlight() {
        String rearCameraId = getRearCameraId();
        if (rearCameraId != null) {
            mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
            try {
                mCameraManager.setTorchMode(rearCameraId, !mTorchEnabled);
                mTorchEnabled = !mTorchEnabled;
            } catch (CameraAccessException e) {
                // Ignore
            }
            doHapticFeedback();
        }
    }

    private void tryLaunchCustom(String packagename) {
        Log.d(TAG, "Disable kg = true");
        disableKGbyScreenOn = true;
        mGestureWakeLock.acquire(GESTURE_WAKELOCK_DURATION);
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), GESTURE_WAKEUP_REASON);
        final Intent intent = getLaunchableIntent(mContext.getPackageManager()
                .getLaunchIntentForPackage(packagename));
        startActivitySafely(intent);
        doHapticFeedback();
    }

    private void playPauseMusic() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        doHapticFeedback();
    }

    private void previousTrack() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        doHapticFeedback();
    }

    private void nextTrack() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT);
        doHapticFeedback();
    }

    private void dispatchMediaKeyWithWakeLockToMediaSession(final int keycode) {
        final MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper == null) {
            Log.w(TAG, "Unable to send media key event");
            return;
        }
        KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
        helper.sendMediaButtonEvent(event, true);
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
        helper.sendMediaButtonEvent(event, true);
    }

    private void startActivitySafely(final Intent intent) {
        if (intent == null) {
            Log.w(TAG, "No intent passed to startActivitySafely");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            final UserHandle user = new UserHandle(UserHandle.USER_CURRENT);
            mContext.startActivityAsUser(intent, null, user);
        } catch (ActivityNotFoundException e) {
            // Ignore
        }
    }

    private void doHapticFeedback() {
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            return;
        }

        final AudioManager audioManager = (AudioManager) mContext.getSystemService(
                Context.AUDIO_SERVICE);
        if (audioManager.getRingerMode() != AudioManager.RINGER_MODE_SILENT) {
            final boolean enabled = CMSettings.System.getInt(mContext.getContentResolver(),
                    CMSettings.System.TOUCHSCREEN_GESTURE_HAPTIC_FEEDBACK, 1) != 0;
            if (enabled) {
                mVibrator.vibrate(50);
            }
        }
    }

    private String getRearCameraId() {
        if (mRearCameraId == null) {
            try {
                for (final String cameraId : mCameraManager.getCameraIdList()) {
                    final CameraCharacteristics characteristics =
                            mCameraManager.getCameraCharacteristics(cameraId);
                    final int orientation = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (orientation == CameraCharacteristics.LENS_FACING_BACK) {
                        mRearCameraId = cameraId;
                        break;
                    }
                }
            } catch (CameraAccessException e) {
                // Ignore
            }
        }
        return mRearCameraId;
    }

    private Intent getLaunchableIntent(Intent intent) {
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resInfo = pm.queryIntentActivities(intent, 0);
        if (resInfo.isEmpty()) {
            return null;
        }
        return pm.getLaunchIntentForPackage(resInfo.get(0).activityInfo.packageName);
    }
}
