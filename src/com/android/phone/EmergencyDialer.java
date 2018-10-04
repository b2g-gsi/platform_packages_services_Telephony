/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.phone;

import static android.telephony.ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.telecom.ParcelableCallAnalytics;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DialerKeyListener;
import android.text.style.TtsSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.colorextraction.drawable.GradientDrawable;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.phone.common.dialpad.DialpadKeyButton;
import com.android.phone.common.util.ViewUtil;
import com.android.phone.common.widget.ResizingTextEditText;
import com.android.phone.ecc.CountryEccInfo;
import com.android.phone.ecc.EccInfoHelper;
import com.android.phone.ecc.IsoToEccProtobufRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * EmergencyDialer is a special dialer that is used ONLY for dialing emergency calls.
 *
 * It's a simplified version of the regular dialer (i.e. the TwelveKeyDialer
 * activity from apps/Contacts) that:
 *   1. Allows ONLY emergency calls to be dialed
 *   2. Disallows voicemail functionality
 *   3. Uses the FLAG_SHOW_WHEN_LOCKED window manager flag to allow this
 *      activity to stay in front of the keyguard.
 *
 * TODO: Even though this is an ultra-simplified version of the normal
 * dialer, there's still lots of code duplication between this class and
 * the TwelveKeyDialer class from apps/Contacts.  Could the common code be
 * moved into a shared base class that would live in the framework?
 * Or could we figure out some way to move *this* class into apps/Contacts
 * also?
 *
 * TODO: Implement emergency dialer shortcut.
 *  Emergency dialer shortcut offer a local emergency number list. Directly clicking a call button
 *  to place an emergency phone call without entering numbers from dialpad.
 *  TODO item:
 *     1.integrate emergency phone number table.
 */
public class EmergencyDialer extends Activity implements View.OnClickListener,
        View.OnLongClickListener, View.OnKeyListener, TextWatcher,
        DialpadKeyButton.OnPressedListener, ColorExtractor.OnColorsChangedListener,
        EmergencyShortcutButton.OnConfirmClickListener, SensorEventListener {

    private class MetricsWriter {
        // Metrics constants indicating the entry type that user opened emergency dialer.
        // This info is sent from system UI with EXTRA_ENTRY_TYPE. Please make them being
        // in sync with those in com.android.systemui.util.EmergencyDialerConstants.
        public static final int ENTRY_TYPE_UNKNOWN = 0;
        public static final int ENTRY_TYPE_LOCKSCREEN_BUTTON = 1;
        public static final int ENTRY_TYPE_POWER_MENU = 2;

        // Metrics constants indicating the UI that user made phone call.
        public static final int CALL_SOURCE_DIALPAD = 0;
        public static final int CALL_SOURCE_SHORTCUT = 1;

        // Metrics constants indicating the phone number type of a call user made.
        public static final int PHONE_NUMBER_TYPE_GENERAL = 0;
        public static final int PHONE_NUMBER_TYPE_EMERGENCY = 1;

        // Metrics constants indicating the actions performed by user.
        public static final int USER_ACTION_NONE = 0x0;
        public static final int USER_ACTION_OPEN_DIALPAD = 0x1;
        public static final int USER_ACTION_OPEN_EMERGENCY_INFO = 0x2;
        public static final int USER_ACTION_MAKE_CALL_VIA_DIALPAD = 0x4;
        public static final int USER_ACTION_MAKE_CALL_VIA_SHORTCUT = 0x8;

        private MetricsLogger mMetricsLogger = new MetricsLogger();

        public void writeMetricsForEnter() {
            int entryType = getIntent().getIntExtra(EXTRA_ENTRY_TYPE, ENTRY_TYPE_UNKNOWN);
            KeyguardManager keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            mMetricsLogger.write(new LogMaker(MetricsEvent.EMERGENCY_DIALER)
                    .setType(MetricsEvent.TYPE_OPEN)
                    .setSubtype(entryType)
                    .addTaggedData(MetricsEvent.FIELD_EMERGENCY_DIALER_IS_SCREEN_LOCKED,
                            keyguard.isKeyguardLocked() ? 1 : 0));
        }

        public void writeMetricsForExit() {
            int entryType = getIntent().getIntExtra(EXTRA_ENTRY_TYPE, ENTRY_TYPE_UNKNOWN);
            long userStayDuration = SystemClock.elapsedRealtime() - mUserEnterTimeMillis;
            mMetricsLogger.write(new LogMaker(MetricsEvent.EMERGENCY_DIALER)
                    .setType(MetricsEvent.TYPE_CLOSE)
                    .setSubtype(entryType)
                    .addTaggedData(MetricsEvent.FIELD_EMERGENCY_DIALER_USER_ACTIONS, mUserActions)
                    .addTaggedData(
                            MetricsEvent.FIELD_EMERGENCY_DIALER_DURATION_MS, userStayDuration));
        }

        public void writeMetricsForMakingCall(int callSource, int phoneNumberType,
                boolean hasShortcut) {
            mMetricsLogger.write(new LogMaker(MetricsEvent.EMERGENCY_DIALER_MAKE_CALL)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .setSubtype(callSource)
                    .addTaggedData(MetricsEvent.FIELD_EMERGENCY_DIALER_PHONE_NUMBER_TYPE,
                            phoneNumberType)
                    .addTaggedData(MetricsEvent.FIELD_EMERGENCY_DIALER_PHONE_NUMBER_HAS_SHORTCUT,
                            hasShortcut ? 1 : 0)
                    .addTaggedData(MetricsEvent.FIELD_EMERGENCY_DIALER_IN_POCKET,
                            mIsProximityNear ? 1 : 0));
        }
    }

    // Keys used with onSaveInstanceState().
    private static final String LAST_NUMBER = "lastNumber";

    // Intent action for this activity.
    public static final String ACTION_DIAL = "com.android.phone.EmergencyDialer.DIAL";

    /**
     * Extra included in {@link #ACTION_DIAL} to indicate the entry type that user starts
     * the emergency dialer.
     */
    public static final String EXTRA_ENTRY_TYPE =
            "com.android.phone.EmergencyDialer.extra.ENTRY_TYPE";

    // List of dialer button IDs.
    private static final int[] DIALER_KEYS = new int[] {
            R.id.one, R.id.two, R.id.three,
            R.id.four, R.id.five, R.id.six,
            R.id.seven, R.id.eight, R.id.nine,
            R.id.star, R.id.zero, R.id.pound };

    // Debug constants.
    private static final boolean DBG = false;
    private static final String LOG_TAG = "EmergencyDialer";

    /** The length of DTMF tones in milliseconds */
    private static final int TONE_LENGTH_MS = 150;

    /** The DTMF tone volume relative to other sounds in the stream */
    private static final int TONE_RELATIVE_VOLUME = 80;

    /** Stream type used to play the DTMF tones off call, and mapped to the volume control keys */
    private static final int DIAL_TONE_STREAM_TYPE = AudioManager.STREAM_DTMF;

    private static final int BAD_EMERGENCY_NUMBER_DIALOG = 0;

    /** 90% opacity, different from other gradients **/
    private static final int BACKGROUND_GRADIENT_ALPHA = 230;

    /** 85% opacity for black background **/
    private static final int BLACK_BACKGROUND_GRADIENT_ALPHA = 217;

    /** Size limit of emergency shortcut buttons container. **/
    private static final int SHORTCUT_SIZE_LIMIT = 3;

    ResizingTextEditText mDigits;
    private View mDialButton;
    private View mDelete;
    private View mEmergencyShortcutView;
    private View mDialpadView;

    private EccInfoHelper mEccInfoHelper;

    private List<EmergencyShortcutButton> mEmergencyShortcutButtonList;
    private EccShortcutAdapter mShortcutAdapter;
    private DataSetObserver mShortcutDataSetObserver = null;

    private ToneGenerator mToneGenerator;
    private Object mToneGeneratorLock = new Object();

    // determines if we want to playback local DTMF tones.
    private boolean mDTMFToneEnabled;

    private EmergencyActionGroup mEmergencyActionGroup;

    // close activity when screen turns off
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                finishAndRemoveTask();
            }
        }
    };

    private String mLastNumber; // last number we tried to dial. Used to restore error dialog.

    // Background gradient
    private ColorExtractor mColorExtractor;
    private GradientDrawable mBackgroundGradient;
    private boolean mSupportsDarkText;

    private boolean mIsWfcEmergencyCallingWarningEnabled;
    private float mDefaultDigitsTextSize;

    private boolean mAreEmergencyDialerShortcutsEnabled;

    private MetricsWriter mMetricsWriter;
    private SensorManager mSensorManager;
    private Sensor mProximitySensor;
    private boolean mIsProximityNear = false;

    /**
     * The time, in millis, since boot when user opened emergency dialer.
     * This is used when calculating the user stay duration for metrics data.
     */
    private long mUserEnterTimeMillis = 0;

    /**
     * Bit flag indicating the actions performed by user. This is used for metrics data.
     */
    private int mUserActions = MetricsWriter.USER_ACTION_NONE;

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // Do nothing
    }

    @Override
    public void onTextChanged(CharSequence input, int start, int before, int changeCount) {
        maybeChangeHintSize();
    }

    @Override
    public void afterTextChanged(Editable input) {
        // Check for special sequences, in particular the "**04" or "**05"
        // sequences that allow you to enter PIN or PUK-related codes.
        //
        // But note we *don't* allow most other special sequences here,
        // like "secret codes" (*#*#<code>#*#*) or IMEI display ("*#06#"),
        // since those shouldn't be available if the device is locked.
        //
        // So we call SpecialCharSequenceMgr.handleCharsForLockedDevice()
        // here, not the regular handleChars() method.
        if (SpecialCharSequenceMgr.handleCharsForLockedDevice(this, input.toString(), this)) {
            // A special sequence was entered, clear the digits
            mDigits.getText().clear();
        }

        updateDialAndDeleteButtonStateEnabledAttr();
        updateTtsSpans();
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mMetricsWriter = new MetricsWriter();
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager != null) {
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }

        // Allow this activity to be displayed in front of the keyguard / lockscreen.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;

        // When no proximity sensor is available, use a shorter timeout.
        // TODO: Do we enable this for non proximity devices any more?
        // lp.userActivityTimeout = USER_ACTIVITY_TIMEOUT_WHEN_NO_PROX_SENSOR;

        getWindow().setAttributes(lp);

        mAreEmergencyDialerShortcutsEnabled = Settings.Global.getInt(getContentResolver(),
                Settings.Global.FASTER_EMERGENCY_PHONE_CALL_ENABLED, 0) != 0;
        Log.d(LOG_TAG, "Enable emergency dialer shortcut: "
                + mAreEmergencyDialerShortcutsEnabled);

        mColorExtractor = new ColorExtractor(this);

        // It does not support dark text theme, when emergency dialer shortcuts are enabled.
        // And the background color is black with 85% opacity.
        if (mAreEmergencyDialerShortcutsEnabled) {
            updateTheme(false);
        } else {
            GradientColors lockScreenColors = mColorExtractor.getColors(WallpaperManager.FLAG_LOCK,
                    ColorExtractor.TYPE_EXTRA_DARK);
            updateTheme(lockScreenColors.supportsDarkText());
        }

        setContentView(R.layout.emergency_dialer);

        mDigits = (ResizingTextEditText) findViewById(R.id.digits);
        mDigits.setKeyListener(DialerKeyListener.getInstance());
        mDigits.setOnClickListener(this);
        mDigits.setOnKeyListener(this);
        mDigits.setLongClickable(false);
        mDigits.setInputType(InputType.TYPE_NULL);
        mDefaultDigitsTextSize = mDigits.getScaledTextSize();
        maybeAddNumberFormatting();

        mBackgroundGradient = new GradientDrawable(this);
        Point displaySize = new Point();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getSize(displaySize);
        mBackgroundGradient.setScreenSize(displaySize.x, displaySize.y);
        mBackgroundGradient.setAlpha(mAreEmergencyDialerShortcutsEnabled
                ? BLACK_BACKGROUND_GRADIENT_ALPHA : BACKGROUND_GRADIENT_ALPHA);
        getWindow().setBackgroundDrawable(mBackgroundGradient);

        // Check for the presence of the keypad
        View view = findViewById(R.id.one);
        if (view != null) {
            setupKeypad();
        }

        mDelete = findViewById(R.id.deleteButton);
        mDelete.setOnClickListener(this);
        mDelete.setOnLongClickListener(this);

        mDialButton = findViewById(R.id.floating_action_button);

        // Check whether we should show the onscreen "Dial" button and co.
        // Read carrier config through the public API because PhoneGlobals is not available when we
        // run as a secondary user.
        CarrierConfigManager configMgr =
                (CarrierConfigManager) getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle carrierConfig =
                configMgr.getConfigForSubId(SubscriptionManager.getDefaultVoiceSubscriptionId());

        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_SHOW_ONSCREEN_DIAL_BUTTON_BOOL)) {
            mDialButton.setOnClickListener(this);
        } else {
            mDialButton.setVisibility(View.GONE);
        }
        mIsWfcEmergencyCallingWarningEnabled = carrierConfig.getInt(
                CarrierConfigManager.KEY_EMERGENCY_NOTIFICATION_DELAY_INT) > -1;
        maybeShowWfcEmergencyCallingWarning();

        ViewUtil.setupFloatingActionButton(mDialButton, getResources());

        if (icicle != null) {
            super.onRestoreInstanceState(icicle);
        }

        // Extract phone number from intent
        Uri data = getIntent().getData();
        if (data != null && (PhoneAccount.SCHEME_TEL.equals(data.getScheme()))) {
            String number = PhoneNumberUtils.getNumberFromIntent(getIntent(), this);
            if (number != null) {
                mDigits.setText(number);
            }
        }

        // if the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new ToneGenerator(DIAL_TONE_STREAM_TYPE, TONE_RELATIVE_VOLUME);
                } catch (RuntimeException e) {
                    Log.w(LOG_TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mBroadcastReceiver, intentFilter);

        mEmergencyActionGroup = (EmergencyActionGroup) findViewById(R.id.emergency_action_group);

        if (mAreEmergencyDialerShortcutsEnabled) {
            mEccInfoHelper = new EccInfoHelper(new IsoToEccProtobufRepository());
            setupEmergencyShortcutsView();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
        unregisterReceiver(mBroadcastReceiver);
        if (mShortcutAdapter != null && mShortcutDataSetObserver != null) {
            mShortcutAdapter.unregisterDataSetObserver(mShortcutDataSetObserver);
            mShortcutDataSetObserver = null;
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle icicle) {
        mLastNumber = icicle.getString(LAST_NUMBER);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(LAST_NUMBER, mLastNumber);
    }

    /**
     * Explicitly turn off number formatting, since it gets in the way of the emergency
     * number detector
     */
    protected void maybeAddNumberFormatting() {
        // Do nothing.
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // This can't be done in onCreate(), since the auto-restoring of the digits
        // will play DTMF tones for all the old digits if it is when onRestoreSavedInstanceState()
        // is called. This method will be called every time the activity is created, and
        // will always happen after onRestoreSavedInstanceState().
        mDigits.addTextChangedListener(this);
    }

    private void setupKeypad() {
        // Setup the listeners for the buttons
        for (int id : DIALER_KEYS) {
            final DialpadKeyButton key = (DialpadKeyButton) findViewById(id);
            key.setOnPressedListener(this);
        }

        View view = findViewById(R.id.zero);
        view.setOnLongClickListener(this);
    }

    @Override
    public void onBackPressed() {
        // If emergency dialer shortcut is enabled and Dialpad view is visible, pressing the
        // back key will back to display EmergencyShortcutView view.
        // Otherwise, it would finish the activity.
        if (mAreEmergencyDialerShortcutsEnabled && mDialpadView != null
                && mDialpadView.getVisibility() == View.VISIBLE) {
            switchView(mEmergencyShortcutView, mDialpadView, true);
            return;
        }
        super.onBackPressed();
    }

    /**
     * handle key events
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // Happen when there's a "Call" hard button.
            case KeyEvent.KEYCODE_CALL: {
                if (TextUtils.isEmpty(mDigits.getText().toString())) {
                    // if we are adding a call from the InCallScreen and the phone
                    // number entered is empty, we just close the dialer to expose
                    // the InCallScreen under it.
                    finish();
                } else {
                    // otherwise, we place the call.
                    placeCall();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void keyPressed(int keyCode) {
        mDigits.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        mDigits.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent event) {
        switch (view.getId()) {
            case R.id.digits:
                // Happen when "Done" button of the IME is pressed. This can happen when this
                // Activity is forced into landscape mode due to a desk dock.
                if (keyCode == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    placeCall();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        onPreTouchEvent(ev);
        boolean handled = super.dispatchTouchEvent(ev);
        onPostTouchEvent(ev);
        return handled;
    }

    @Override
    public void onConfirmClick(EmergencyShortcutButton button) {
        if (button == null) return;

        mUserActions |= MetricsWriter.USER_ACTION_MAKE_CALL_VIA_SHORTCUT;

        // We interest on the context when user has intention to make phone call,
        // so write metrics here for shortcut number even the call may not be created.
        mMetricsWriter.writeMetricsForMakingCall(MetricsWriter.CALL_SOURCE_SHORTCUT,
                MetricsWriter.PHONE_NUMBER_TYPE_EMERGENCY, true);

        String phoneNumber = button.getPhoneNumber();

        if (!TextUtils.isEmpty(phoneNumber)) {
            if (DBG) Log.d(LOG_TAG, "dial emergency number: " + Rlog.pii(LOG_TAG, phoneNumber));
            Bundle extras = new Bundle();
            extras.putInt(TelecomManager.EXTRA_CALL_SOURCE,
                    ParcelableCallAnalytics.CALL_SOURCE_EMERGENCY_SHORTCUT);
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, phoneNumber, null), extras);
        } else {
            Log.d(LOG_TAG, "emergency number is empty");
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.deleteButton: {
                keyPressed(KeyEvent.KEYCODE_DEL);
                return;
            }
            case R.id.floating_action_button: {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                placeCall();
                return;
            }
            case R.id.digits: {
                if (mDigits.length() != 0) {
                    mDigits.setCursorVisible(true);
                }
                return;
            }
            case R.id.floating_action_button_dialpad: {
                mUserActions |= MetricsWriter.USER_ACTION_OPEN_DIALPAD;
                mDigits.getText().clear();
                switchView(mDialpadView, mEmergencyShortcutView, true);
                return;
            }
            case R.id.emergency_info_button: {
                mUserActions |= MetricsWriter.USER_ACTION_OPEN_EMERGENCY_INFO;
                Intent intent = (Intent) view.getTag(R.id.tag_intent);
                if (intent != null) {
                    startActivity(intent);
                }
                return;
            }
        }
    }

    @Override
    public void onPressed(View view, boolean pressed) {
        if (!pressed) {
            return;
        }
        switch (view.getId()) {
            case R.id.one: {
                playTone(ToneGenerator.TONE_DTMF_1);
                keyPressed(KeyEvent.KEYCODE_1);
                return;
            }
            case R.id.two: {
                playTone(ToneGenerator.TONE_DTMF_2);
                keyPressed(KeyEvent.KEYCODE_2);
                return;
            }
            case R.id.three: {
                playTone(ToneGenerator.TONE_DTMF_3);
                keyPressed(KeyEvent.KEYCODE_3);
                return;
            }
            case R.id.four: {
                playTone(ToneGenerator.TONE_DTMF_4);
                keyPressed(KeyEvent.KEYCODE_4);
                return;
            }
            case R.id.five: {
                playTone(ToneGenerator.TONE_DTMF_5);
                keyPressed(KeyEvent.KEYCODE_5);
                return;
            }
            case R.id.six: {
                playTone(ToneGenerator.TONE_DTMF_6);
                keyPressed(KeyEvent.KEYCODE_6);
                return;
            }
            case R.id.seven: {
                playTone(ToneGenerator.TONE_DTMF_7);
                keyPressed(KeyEvent.KEYCODE_7);
                return;
            }
            case R.id.eight: {
                playTone(ToneGenerator.TONE_DTMF_8);
                keyPressed(KeyEvent.KEYCODE_8);
                return;
            }
            case R.id.nine: {
                playTone(ToneGenerator.TONE_DTMF_9);
                keyPressed(KeyEvent.KEYCODE_9);
                return;
            }
            case R.id.zero: {
                playTone(ToneGenerator.TONE_DTMF_0);
                keyPressed(KeyEvent.KEYCODE_0);
                return;
            }
            case R.id.pound: {
                playTone(ToneGenerator.TONE_DTMF_P);
                keyPressed(KeyEvent.KEYCODE_POUND);
                return;
            }
            case R.id.star: {
                playTone(ToneGenerator.TONE_DTMF_S);
                keyPressed(KeyEvent.KEYCODE_STAR);
                return;
            }
        }
    }

    /**
     * called for long touch events
     */
    @Override
    public boolean onLongClick(View view) {
        int id = view.getId();
        switch (id) {
            case R.id.deleteButton: {
                mDigits.getText().clear();
                return true;
            }
            case R.id.zero: {
                removePreviousDigitIfPossible();
                keyPressed(KeyEvent.KEYCODE_PLUS);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onStart() {
        super.onStart();

        mUserEnterTimeMillis = SystemClock.elapsedRealtime();
        mUserActions = MetricsWriter.USER_ACTION_NONE;
        mMetricsWriter.writeMetricsForEnter();

        // It does not support dark text theme, when emergency dialer shortcuts are enabled.
        // And set background color to black.
        if (mAreEmergencyDialerShortcutsEnabled) {
            mBackgroundGradient.setColors(Color.BLACK, Color.BLACK, false);
            updateTheme(false);
        } else {
            mColorExtractor.addOnColorsChangedListener(this);
            GradientColors lockScreenColors = mColorExtractor.getColors(WallpaperManager.FLAG_LOCK,
                    ColorExtractor.TYPE_EXTRA_DARK);
            // Do not animate when view isn't visible yet, just set an initial state.
            mBackgroundGradient.setColors(lockScreenColors, false);
            updateTheme(lockScreenColors.supportsDarkText());
        }

        if (mAreEmergencyDialerShortcutsEnabled && mEccInfoHelper != null) {
            final Context context = this;
            mEccInfoHelper.getCountryEccInfoAsync(context,
                    new EccInfoHelper.CountryEccInfoResultCallback() {
                        @Override
                        public void onSuccess(String iso, CountryEccInfo countryEccInfo) {
                            Log.d(LOG_TAG, "Retrieve ECC info success, country ISO: "
                                    + Rlog.pii(LOG_TAG, iso));
                            updateLocationAndEccInfo(iso, countryEccInfo);
                        }

                        @Override
                        public void onDetectCountryFailed() {
                            Log.w(LOG_TAG, "Cannot detect current country.");
                            updateLocationAndEccInfo(null, null);
                        }

                        @Override
                        public void onRetrieveCountryEccInfoFailed(String iso) {
                            Log.w(LOG_TAG, "Retrieve ECC info failed, country ISO: "
                                    + Rlog.pii(LOG_TAG, iso));
                            updateLocationAndEccInfo(iso, null);
                        }
                    });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mProximitySensor != null) {
            mSensorManager.registerListener(
                    this, mProximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        // retrieve the DTMF tone play back setting.
        mDTMFToneEnabled = Settings.System.getInt(getContentResolver(),
                Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1;

        // if the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new ToneGenerator(AudioManager.STREAM_DTMF,
                            TONE_RELATIVE_VOLUME);
                } catch (RuntimeException e) {
                    Log.w(LOG_TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }

        updateDialAndDeleteButtonStateEnabledAttr();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mProximitySensor != null) {
            mSensorManager.unregisterListener(this, mProximitySensor);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mMetricsWriter.writeMetricsForExit();
        mColorExtractor.removeOnColorsChangedListener(this);
    }

    /**
     * Sets theme based on gradient colors
     * @param supportsDarkText true if gradient supports dark text
     */
    private void updateTheme(boolean supportsDarkText) {
        if (mSupportsDarkText == supportsDarkText) {
            return;
        }
        mSupportsDarkText = supportsDarkText;

        // We can't change themes after inflation, in this case we'll have to recreate
        // the whole activity.
        if (mBackgroundGradient != null) {
            recreate();
            return;
        }

        int vis = getWindow().getDecorView().getSystemUiVisibility();
        if (supportsDarkText) {
            vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            setTheme(R.style.EmergencyDialerThemeDark);
        } else {
            vis &= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            vis &= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            setTheme(R.style.EmergencyDialerTheme);
        }
        getWindow().getDecorView().setSystemUiVisibility(vis);
    }

    /**
     * place the call, but check to make sure it is a viable number.
     */
    private void placeCall() {
        mUserActions |= MetricsWriter.USER_ACTION_MAKE_CALL_VIA_DIALPAD;
        mLastNumber = mDigits.getText().toString();

        // Convert into emergency number according to emergency conversion map.
        // If conversion map is not defined (this is default), this method does
        // nothing and just returns input number.
        mLastNumber = PhoneNumberUtils.convertToEmergencyNumber(this, mLastNumber);

        if (PhoneNumberUtils.isLocalEmergencyNumber(this, mLastNumber)) {
            if (DBG) Log.d(LOG_TAG, "placing call to " + mLastNumber);

            // place the call if it is a valid number
            if (mLastNumber == null || !TextUtils.isGraphic(mLastNumber)) {
                // There is no number entered.
                playTone(ToneGenerator.TONE_PROP_NACK);
                return;
            }

            mMetricsWriter.writeMetricsForMakingCall(MetricsWriter.CALL_SOURCE_DIALPAD,
                    MetricsWriter.PHONE_NUMBER_TYPE_EMERGENCY, isShortcutNumber(mLastNumber));

            Bundle extras = new Bundle();
            extras.putInt(TelecomManager.EXTRA_CALL_SOURCE,
                    ParcelableCallAnalytics.CALL_SOURCE_EMERGENCY_DIALPAD);
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, mLastNumber, null), extras);
        } else {
            if (DBG) Log.d(LOG_TAG, "rejecting bad requested number " + mLastNumber);

            // We interest on the context when user has intention to make phone call,
            // so write metrics here for non-emergency numbers even these numbers are rejected.
            mMetricsWriter.writeMetricsForMakingCall(MetricsWriter.CALL_SOURCE_DIALPAD,
                    MetricsWriter.PHONE_NUMBER_TYPE_GENERAL, false);

            showDialog(BAD_EMERGENCY_NUMBER_DIALOG);
        }
        mDigits.getText().delete(0, mDigits.getText().length());
    }

    /**
     * Plays the specified tone for TONE_LENGTH_MS milliseconds.
     *
     * The tone is played locally, using the audio stream for phone calls.
     * Tones are played only if the "Audible touch tones" user preference
     * is checked, and are NOT played if the device is in silent mode.
     *
     * @param tone a tone code from {@link ToneGenerator}
     */
    void playTone(int tone) {
        // if local tone playback is disabled, just return.
        if (!mDTMFToneEnabled) {
            return;
        }

        // Also do nothing if the phone is in silent mode.
        // We need to re-check the ringer mode for *every* playTone()
        // call, rather than keeping a local flag that's updated in
        // onResume(), since it's possible to toggle silent mode without
        // leaving the current activity (via the ENDCALL-longpress menu.)
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if ((ringerMode == AudioManager.RINGER_MODE_SILENT)
            || (ringerMode == AudioManager.RINGER_MODE_VIBRATE)) {
            return;
        }

        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                Log.w(LOG_TAG, "playTone: mToneGenerator == null, tone: " + tone);
                return;
            }

            // Start the new tone (will stop any playing tone)
            mToneGenerator.startTone(tone, TONE_LENGTH_MS);
        }
    }

    private CharSequence createErrorMessage(String number) {
        if (!TextUtils.isEmpty(number)) {
            String errorString = getString(R.string.dial_emergency_error, number);
            int startingPosition = errorString.indexOf(number);
            int endingPosition = startingPosition + number.length();
            Spannable result = new SpannableString(errorString);
            PhoneNumberUtils.addTtsSpan(result, startingPosition, endingPosition);
            return result;
        } else {
            return getText(R.string.dial_emergency_empty_error).toString();
        }
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        AlertDialog dialog = null;
        if (id == BAD_EMERGENCY_NUMBER_DIALOG) {
            // construct dialog
            dialog = new AlertDialog.Builder(this, R.style.EmergencyDialerAlertDialogTheme)
                    .setTitle(getText(R.string.emergency_enable_radio_dialog_title))
                    .setMessage(createErrorMessage(mLastNumber))
                    .setPositiveButton(R.string.ok, null)
                    .setCancelable(true).create();

            // blur stuff behind the dialog
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            setShowWhenLocked(true);
        }
        return dialog;
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);
        if (id == BAD_EMERGENCY_NUMBER_DIALOG) {
            AlertDialog alert = (AlertDialog) dialog;
            alert.setMessage(createErrorMessage(mLastNumber));
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Update the enabledness of the "Dial" and "Backspace" buttons if applicable.
     */
    private void updateDialAndDeleteButtonStateEnabledAttr() {
        final boolean notEmpty = mDigits.length() != 0;

        mDelete.setEnabled(notEmpty);
    }

    /**
     * Remove the digit just before the current position. Used by various long pressed callbacks
     * to remove the digit that was populated as a result of the short click.
     */
    private void removePreviousDigitIfPossible() {
        final int currentPosition = mDigits.getSelectionStart();
        if (currentPosition > 0) {
            mDigits.setSelection(currentPosition);
            mDigits.getText().delete(currentPosition - 1, currentPosition);
        }
    }

    /**
     * Update the text-to-speech annotations in the edit field.
     */
    private void updateTtsSpans() {
        for (Object o : mDigits.getText().getSpans(0, mDigits.getText().length(), TtsSpan.class)) {
            mDigits.getText().removeSpan(o);
        }
        PhoneNumberUtils.ttsSpanAsPhoneNumber(mDigits.getText(), 0, mDigits.getText().length());
    }

    @Override
    public void onColorsChanged(ColorExtractor extractor, int which) {
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            GradientColors colors = extractor.getColors(WallpaperManager.FLAG_LOCK,
                    ColorExtractor.TYPE_EXTRA_DARK);
            mBackgroundGradient.setColors(colors);
            updateTheme(colors.supportsDarkText());
        }
    }

    /**
     * Where a carrier requires a warning that emergency calling is not available while on WFC,
     * add hint text above the dial pad which warns the user of this case.
     */
    private void maybeShowWfcEmergencyCallingWarning() {
        if (!mIsWfcEmergencyCallingWarningEnabled) {
            Log.i(LOG_TAG, "maybeShowWfcEmergencyCallingWarning: warning disabled by carrier.");
            return;
        }

        // Use an async task rather than calling into Telephony on UI thread.
        AsyncTask<Void, Void, Boolean> showWfcWarningTask = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                boolean isWfcAvailable = tm.isWifiCallingAvailable();
                ServiceState ss = tm.getServiceState();
                boolean isCellAvailable =
                        ss.getRilVoiceRadioTechnology() != RIL_RADIO_TECHNOLOGY_UNKNOWN;
                Log.i(LOG_TAG, "showWfcWarningTask: isWfcAvailable=" + isWfcAvailable
                                + " isCellAvailable=" + isCellAvailable
                                + "(rat=" + ss.getRilVoiceRadioTechnology() + ")");
                return isWfcAvailable && !isCellAvailable;
            }

            @Override
            protected void onPostExecute(Boolean result) {
                if (result.booleanValue()) {
                    Log.i(LOG_TAG, "showWfcWarningTask: showing ecall warning");
                    mDigits.setHint(R.string.dial_emergency_calling_not_available);
                } else {
                    Log.i(LOG_TAG, "showWfcWarningTask: hiding ecall warning");
                    mDigits.setHint("");
                }
                maybeChangeHintSize();
            }
        };
        showWfcWarningTask.execute((Void) null);
    }

    /**
     * Where a hint is applied and there are no digits dialed, disable autoresize of the dial digits
     * edit view and set the font size to a smaller size appropriate for the emergency calling
     * warning.
     */
    private void maybeChangeHintSize() {
        if (TextUtils.isEmpty(mDigits.getHint())
                || !TextUtils.isEmpty(mDigits.getText().toString())) {
            // No hint or there are dialed digits, so use default size.
            mDigits.setTextSize(TypedValue.COMPLEX_UNIT_SP, mDefaultDigitsTextSize);
            // By default, the digits view auto-resizes to fit the text it contains, so
            // enable that now.
            mDigits.setResizeEnabled(true);
            Log.i(LOG_TAG, "no hint - setting to " + mDigits.getScaledTextSize());
        } else {
            // Hint present and no dialed digits, set custom font size appropriate for the warning.
            mDigits.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(
                    R.dimen.emergency_call_warning_size));
            // Since we're populating this with a static text string, disable auto-resize.
            mDigits.setResizeEnabled(false);
            Log.i(LOG_TAG, "hint - setting to " + mDigits.getScaledTextSize());
        }
    }

    private void setupEmergencyShortcutsView() {
        mEmergencyShortcutView = findViewById(R.id.emergency_dialer_shortcuts);
        mDialpadView = findViewById(R.id.emergency_dialer);

        final View dialpadButton = findViewById(R.id.floating_action_button_dialpad);
        dialpadButton.setOnClickListener(this);

        final View emergencyInfoButton = findViewById(R.id.emergency_info_button);
        emergencyInfoButton.setOnClickListener(this);

        // EmergencyActionGroup is replaced by EmergencyInfoGroup.
        mEmergencyActionGroup.setVisibility(View.GONE);

        // Setup dialpad title.
        final View emergencyDialpadTitle = findViewById(R.id.emergency_dialpad_title_container);
        emergencyDialpadTitle.setVisibility(View.VISIBLE);

        mEmergencyShortcutButtonList = new ArrayList<>();
        setupEmergencyCallShortcutButton();

        updateLocationAndEccInfo(null, null);

        switchView(mEmergencyShortcutView, mDialpadView, false);
    }

    private void setLocationInfo(String countryIso) {
        final View locationInfo = findViewById(R.id.location_info);

        String countryName = null;
        if (!TextUtils.isEmpty(countryIso)) {
            Locale locale = Locale.getDefault();
            countryName = new Locale(locale.getLanguage(), countryIso, locale.getVariant())
                    .getDisplayCountry();
        }
        if (TextUtils.isEmpty(countryName)) {
            locationInfo.setVisibility(View.INVISIBLE);
        } else {
            final TextView location = (TextView) locationInfo.findViewById(R.id.location_text);
            location.setText(countryName);
            locationInfo.setVisibility(View.VISIBLE);
        }
    }

    private void setupEmergencyCallShortcutButton() {
        final ViewGroup shortcutButtonContainer = findViewById(
                R.id.emergency_shortcut_buttons_container);
        shortcutButtonContainer.setClipToOutline(true);
        final TextView emergencyNumberTitle = findViewById(R.id.emergency_number_title);

        mShortcutAdapter = new EccShortcutAdapter(this) {
            @Override
            public View inflateView(View convertView, ViewGroup parent, CharSequence number,
                    CharSequence description, int iconRes) {
                EmergencyShortcutButton button = (EmergencyShortcutButton) getLayoutInflater()
                        .inflate(R.layout.emergency_shortcut_button, parent, false);
                button.setPhoneNumber(number);
                button.setPhoneDescription(description);
                button.setPhoneTypeIcon(iconRes);
                button.setOnConfirmClickListener(EmergencyDialer.this);
                return button;
            }
        };
        mShortcutDataSetObserver = new DataSetObserver() {
            @Override
            public void onChanged() {
                super.onChanged();
                updateLayout();
            }

            @Override
            public void onInvalidated() {
                super.onInvalidated();
                updateLayout();
            }

            private void updateLayout() {
                // clear previous added buttons
                shortcutButtonContainer.removeAllViews();
                mEmergencyShortcutButtonList.clear();

                for (int i = 0; i < mShortcutAdapter.getCount() && i < SHORTCUT_SIZE_LIMIT; ++i) {
                    EmergencyShortcutButton button = (EmergencyShortcutButton)
                            mShortcutAdapter.getView(i, null, shortcutButtonContainer);
                    mEmergencyShortcutButtonList.add(button);
                    shortcutButtonContainer.addView(button);
                }

                // update emergency numbers title for numerous buttons.
                if (mEmergencyShortcutButtonList.size() > 1) {
                    emergencyNumberTitle.setText(getString(
                            R.string.numerous_emergency_numbers_title));
                } else {
                    emergencyNumberTitle.setText(getText(R.string.single_emergency_number_title));
                }
            }
        };
        mShortcutAdapter.registerDataSetObserver(mShortcutDataSetObserver);
    }

    private void updateLocationAndEccInfo(String iso, CountryEccInfo countryEccInfo) {
        if (!isFinishing() && !isDestroyed()) {
            setLocationInfo(iso);
            if (mShortcutAdapter != null) {
                mShortcutAdapter.updateCountryEccInfo(this, countryEccInfo);
            }
        }
    }

    /**
     * Called by the activity before a touch event is dispatched to the view hierarchy.
     */
    private void onPreTouchEvent(MotionEvent event) {
        mEmergencyActionGroup.onPreTouchEvent(event);

        if (mEmergencyShortcutButtonList != null) {
            for (EmergencyShortcutButton button : mEmergencyShortcutButtonList) {
                button.onPreTouchEvent(event);
            }
        }
    }

    /**
     * Called by the activity after a touch event is dispatched to the view hierarchy.
     */
    private void onPostTouchEvent(MotionEvent event) {
        mEmergencyActionGroup.onPostTouchEvent(event);

        if (mEmergencyShortcutButtonList != null) {
            for (EmergencyShortcutButton button : mEmergencyShortcutButtonList) {
                button.onPostTouchEvent(event);
            }
        }
    }

    /**
     * Switch two view.
     *
     * @param displayView the view would be displayed.
     * @param hideView the view would be hidden.
     * @param hasAnimation is {@code true} when the view should be displayed with animation.
     */
    private void switchView(View displayView, View hideView, boolean hasAnimation) {
        if (displayView == null || hideView == null) {
            return;
        }

        if (displayView.getVisibility() == View.VISIBLE) {
            return;
        }

        if (hasAnimation) {
            crossfade(hideView, displayView);
        } else {
            hideView.setVisibility(View.GONE);
            displayView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Fade out and fade in animation between two view transition.
     */
    private void crossfade(View fadeOutView, View fadeInView) {
        if (fadeOutView == null || fadeInView == null) {
            return;
        }
        final int shortAnimationDuration = getResources().getInteger(
                android.R.integer.config_shortAnimTime);

        fadeInView.setAlpha(0f);
        fadeInView.setVisibility(View.VISIBLE);

        fadeInView.animate()
                .alpha(1f)
                .setDuration(shortAnimationDuration)
                .setListener(null);

        fadeOutView.animate()
                .alpha(0f)
                .setDuration(shortAnimationDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        fadeOutView.setVisibility(View.GONE);
                    }
                });
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float distance = event.values[0];
        mIsProximityNear = (distance < mProximitySensor.getMaximumRange());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used.
    }

    private boolean isShortcutNumber(String number) {
        if (TextUtils.isEmpty(number) || mEmergencyShortcutButtonList == null) {
            return false;
        }

        boolean isShortcut = false;
        for (EmergencyShortcutButton button : mEmergencyShortcutButtonList) {
            if (button != null && number.equals(button.getPhoneNumber())) {
                isShortcut = true;
                break;
            }
        }
        return isShortcut;
    }
}
