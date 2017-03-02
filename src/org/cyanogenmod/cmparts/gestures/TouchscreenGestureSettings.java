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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.UserHandle;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;

import cyanogenmod.hardware.CMHardwareManager;
import cyanogenmod.hardware.TouchscreenGesture;

import org.cyanogenmod.cmparts.R;
import org.cyanogenmod.cmparts.SettingsPreferenceFragment;
import org.cyanogenmod.cmparts.utils.ResourceUtils;

import java.lang.System;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class TouchscreenGestureSettings extends SettingsPreferenceFragment {
    private static final String TAG = TouchscreenGestureSettings.class.getSimpleName();

    private static final String KEY_TOUCHSCREEN_GESTURE = "touchscreen_gesture";
    private static final String TOUCHSCREEN_GESTURE_TITLE = KEY_TOUCHSCREEN_GESTURE + "_%s_title";

    private TouchscreenGesture[] mTouchscreenGestures;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.touchscreen_gesture_settings);

        if (isTouchscreenGesturesSupported(getContext())) {
            initTouchscreenGestures();
        }
    }

    private void initTouchscreenGestures() {
        TreeMap<String, String> treemap = new TreeMap<String, String>(new Comparator<String>() {
            public int compare(String o1, String o2) {
                return o1.toLowerCase().compareTo(o2.toLowerCase());
            }
        });

        List<String> listPackageNames = getPackageNames();
        for (String name : listPackageNames){
            treemap.put(getAppnameFromPackagename(name), name);
        }

        ArrayList<CharSequence> packageNames = new ArrayList<CharSequence>();
        ArrayList<CharSequence> hrblPackageNames = new ArrayList<CharSequence>();

        CharSequence[] defaultGesturesNames =
                getContext().getResources().getStringArray(R.array.touchscreen_gesture_action_entries);
        CharSequence[] defaultGesturesActions =
                getContext().getResources().getStringArray(R.array.touchscreen_gesture_action_values);

        for(CharSequence cs : defaultGesturesNames){
            hrblPackageNames.add(cs);
        }
        for(CharSequence cs : defaultGesturesActions) {
            packageNames.add(cs);
        }

        Iterator ittwo = treemap.entrySet().iterator();
        while (ittwo.hasNext()) {
            Map.Entry pairs = (Map.Entry)ittwo.next();
            hrblPackageNames.add((CharSequence)pairs.getKey());
            packageNames.add((CharSequence)pairs.getValue());
            ittwo.remove();
        }

        final CharSequence[] packageNamesCharsq =
                packageNames.toArray(new CharSequence[packageNames.size()]);
        final CharSequence[] hrblPackageNamesCharsq =
                hrblPackageNames.toArray(new CharSequence[hrblPackageNames.size()]);

        final CMHardwareManager manager = CMHardwareManager.getInstance(getContext());
        mTouchscreenGestures = manager.getTouchscreenGestures();
        final int[] actions = getDefaultGestureActions(getContext(), mTouchscreenGestures);
        for (final TouchscreenGesture gesture : mTouchscreenGestures) {
            getPreferenceScreen().addPreference(new TouchscreenGesturePreference(
                    getContext(), gesture, actions[gesture.id],
                    packageNamesCharsq, hrblPackageNamesCharsq));
        }
    }

    private class TouchscreenGesturePreference extends ListPreference {
        private final Context mContext;
        private final TouchscreenGesture mGesture;

        public TouchscreenGesturePreference(final Context context,
                                            final TouchscreenGesture gesture,
                                            final int defaultAction,
                                            final CharSequence[] packageNamesCharsq,
                                            final CharSequence[] hrblPackageNamesCharsq) {
            super(context);
            mContext = context;
            mGesture = gesture;

            setKey(buildPreferenceKey(gesture));
            //setEntries(R.array.touchscreen_gesture_action_entries);
            //setEntryValues(R.array.touchscreen_gesture_action_values);
            setEntries(hrblPackageNamesCharsq);
            setEntryValues(packageNamesCharsq);
            setDefaultValue(String.valueOf(defaultAction));
            setIcon(getIconDrawableResourceForAction(String.valueOf(defaultAction)));
            setSummary("%s");
            setDialogTitle(R.string.touchscreen_gesture_action_dialog_title);
            setTitle(ResourceUtils.getLocalizedString(
                    context.getResources(), gesture.name, TOUCHSCREEN_GESTURE_TITLE));
        }

        @Override
        public boolean callChangeListener(final Object newValue) {
            boolean setEnable = false;
            try {
                final int action = Integer.parseInt(String.valueOf(newValue));
                setEnable = (action > 0);
            } catch (NumberFormatException e){
                setEnable = true;
            }
            final CMHardwareManager manager = CMHardwareManager.getInstance(mContext);
            if (!manager.setTouchscreenGestureEnabled(mGesture, setEnable)) {
                return false;
            }
            return super.callChangeListener(newValue);
        }

        @Override
        protected boolean persistString(String value) {
            if (!super.persistString(value)) {
                return false;
            }
            setIcon(getIconDrawableResourceForAction(value));
            sendUpdateBroadcast(mContext, mTouchscreenGestures);
            return true;
        }

        private Drawable getIconDrawableResourceForAction(final String action) {
            int actionInt = 0;
            try {
                actionInt = Integer.parseInt(String.valueOf(action));

            } catch (NumberFormatException e){
                actionInt = TouchscreenGestureConstants.ACTION_CUSTOM;
            }
            switch (actionInt) {
                case TouchscreenGestureConstants.ACTION_CAMERA:
                    return getResources().getDrawable(R.drawable.ic_gesture_action_camera,
                            mContext.getTheme());
                case TouchscreenGestureConstants.ACTION_FLASHLIGHT:
                    return getResources().getDrawable(R.drawable.ic_gesture_action_flashlight,
                            mContext.getTheme());
                case TouchscreenGestureConstants.ACTION_BROWSER:
                    return getResources().getDrawable(R.drawable.ic_gesture_action_browser,
                            mContext.getTheme());
                case TouchscreenGestureConstants.ACTION_DIALER:
                    return getResources().getDrawable(R.drawable.ic_gesture_action_dialer,
                            mContext.getTheme());
                case TouchscreenGestureConstants.ACTION_EMAIL:
                    return getResources().getDrawable(R.drawable.ic_gesture_action_email,
                            mContext.getTheme());
                case TouchscreenGestureConstants.ACTION_MESSAGES:
                    return getResources().getDrawable(R.drawable.ic_gesture_action_messages,
                            mContext.getTheme());
                case TouchscreenGestureConstants.ACTION_PLAY_PAUSE_MUSIC:
                    return getResources().getDrawable(R.drawable.ic_gesture_action_play_pause,
                            mContext.getTheme());
                case TouchscreenGestureConstants.ACTION_PREVIOUS_TRACK:
                    return getResources().getDrawable(R.drawable.ic_gesture_action_previous_track,
                            mContext.getTheme());
                case TouchscreenGestureConstants.ACTION_NEXT_TRACK:
                    return getResources().getDrawable(R.drawable.ic_gesture_action_next_track,
                            mContext.getTheme());
                case TouchscreenGestureConstants.ACTION_CUSTOM:
                    try {
                        Drawable icon = mContext.getPackageManager().getApplicationIcon(action);
                        return resizeIconTo(icon,getResources().getDrawable(
                                R.drawable.ic_gesture_action_none, mContext.getTheme()));
                    } catch (PackageManager.NameNotFoundException e){
                        return getResources().getDrawable(R.drawable.ic_gesture_action_none,
                                mContext.getTheme());
                    }
                default:
                    // No gesture action
                    return getResources().getDrawable(R.drawable.ic_gesture_action_none,
                            mContext.getTheme());
            }
        }

        private Drawable resizeIconTo(Drawable iconToResize, Drawable sizeSrc) {
            Bitmap bIconToResize = ((BitmapDrawable)iconToResize).getBitmap();
            Bitmap bitmapResized = Bitmap.createScaledBitmap(bIconToResize,
                    sizeSrc.getIntrinsicWidth(), sizeSrc.getIntrinsicHeight(), false);
            return new BitmapDrawable(getResources(), bitmapResized);
        }
    }

    public static void restoreTouchscreenGestureStates(final Context context) {
        if (!isTouchscreenGesturesSupported(context)) {
            return;
        }

        final CMHardwareManager manager = CMHardwareManager.getInstance(context);
        final TouchscreenGesture[] gestures = manager.getTouchscreenGestures();
        final String[] actionList = buildActionList(context, gestures);
        for (final TouchscreenGesture gesture : gestures) {
            try {
                final int action = Integer.parseInt(String.valueOf(actionList[gesture.id]));
                manager.setTouchscreenGestureEnabled(gesture, action > 0);
            } catch (NumberFormatException e){
                manager.setTouchscreenGestureEnabled(gesture, true);
            }
        }
        sendUpdateBroadcast(context, gestures);
    }

    private static boolean isTouchscreenGesturesSupported(final Context context) {
        final CMHardwareManager manager = CMHardwareManager.getInstance(context);
        return manager.isSupported(CMHardwareManager.FEATURE_TOUCHSCREEN_GESTURES);
    }

    private static int[] getDefaultGestureActions(final Context context,
                                                  final TouchscreenGesture[] gestures) {
        final int[] defaultActions = context.getResources().getIntArray(
                R.array.config_defaultTouchscreenGestureActions);
        if (defaultActions.length >= gestures.length) {
            return defaultActions;
        }

        final int[] filledDefaultActions = new int[gestures.length];
        System.arraycopy(defaultActions, 0, filledDefaultActions, 0, defaultActions.length);
        return filledDefaultActions;
    }

    private static String[] buildActionList(final Context context,
                                         final TouchscreenGesture[] gestures) {
        final String[] result = new String[gestures.length];
        final int[] defaultActions = getDefaultGestureActions(context, gestures);
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        for (final TouchscreenGesture gesture : gestures) {
            final String key = buildPreferenceKey(gesture);
            final String defaultValue = String.valueOf(defaultActions[gesture.id]);
            result[gesture.id] = prefs.getString(key, defaultValue);
        }
        return result;
    }

    private static String buildPreferenceKey(final TouchscreenGesture gesture) {
        return "touchscreen_gesture_" + gesture.id;
    }

    private static void sendUpdateBroadcast(final Context context,
                                            final TouchscreenGesture[] gestures) {
        final Intent intent = new Intent(TouchscreenGestureConstants.UPDATE_PREFS_ACTION);
        final int[] keycodes = new int[gestures.length];
        final String[] actions = buildActionList(context, gestures);
        for (final TouchscreenGesture gesture : gestures) {
            keycodes[gesture.id] = gesture.keycode;
        }
        intent.putExtra(TouchscreenGestureConstants.UPDATE_EXTRA_KEYCODE_MAPPING, keycodes);
        intent.putExtra(TouchscreenGestureConstants.UPDATE_EXTRA_ACTION_MAPPING, actions);
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        context.sendBroadcastAsUser(intent, UserHandle.CURRENT);
    }

    private List<String> getPackageNames(){
        List<String> packageNameList = new ArrayList<String>();
        List<PackageInfo> packs =
                getContext().getPackageManager().getInstalledPackages(0);
        for(int i = 0; i < packs.size(); i++){
            String packageName = packs.get(i).packageName;
            Intent launchIntent = getContext().getPackageManager()
                    .getLaunchIntentForPackage(packageName);
            if(launchIntent != null){
                packageNameList.add(packageName);
            }
        }
        return packageNameList;
    }

    private String getAppnameFromPackagename(String packagename){
        if(packagename == null || "".equals(packagename)){
            return "FIX THIS"; //getResources().getString(R.string.touchscreen_action_default);
        }
        final PackageManager pm = getContext().getPackageManager();
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(packagename, 0);
        } catch (final Exception e) {
            ai = null;
        }
        return (String) (ai != null ? pm.getApplicationLabel(ai) : packagename);
    }
}
