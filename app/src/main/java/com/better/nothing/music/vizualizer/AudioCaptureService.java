package com.better.nothing.music.vizualizer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.app.NotificationCompat;

import com.nothing.ketchum.Common;
import com.nothing.ketchum.GlyphException;
import com.nothing.ketchum.GlyphManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioCaptureService extends Service {

    private static final String TAG = "GlyphViz:Service";
    private static final String CHANNEL_ID = "glyph_viz_channel";
    private static final int NOTIF_ID = 1;
    private static final String ACTION_STOP = "com.better.nothing.music.vizualizer.action.STOP";

    public static final String EXTRA_PRESET_KEY = "preset_key";
    public static final float DEFAULT_GAMMA = 2f;

    private static final String PREFS_NAME = "glyph_visualizer_prefs";
    private static final String APP_PREFS_NAME = "viz_prefs";
    private static final String PREF_GAMMA = "gamma";
    private static final String PREF_LATENCY_PREFIX = "latency_device_";
    private static final String PREF_LATENCY_ROUTE_PREFIX = "latency_route_";
    private static final String PREF_LATENCY_PRESETS = "latency_presets";

    private static final String DEFAULT_PRESET_KEY = "np1s";
    private static final String PHONE_MODEL_UNKNOWN = "UNKNOWN";
    private static final String PHONE_MODEL_PHONE1 = "PHONE1";
    private static final String PHONE_MODEL_PHONE2 = "PHONE2";
    private static final String PHONE_MODEL_PHONE2A = "PHONE2A";
    private static final String PHONE_MODEL_PHONE3A = "PHONE3A";
    private static final String PHONE_MODEL_PHONE3 = "PHONE3";
    private static final String PHONE_MODEL_PHONE4A = "PHONE4A";

    private static final int SAMPLE_RATE = 44100;
    private static final int FPS = 60;
    private static final int HOP = Math.round(SAMPLE_RATE / (float) FPS);
    private static final int ANALYSIS_WINDOW = 1102;
    private static final int FFT_SIZE = 2048;
    private static final float HZ_PER_BIN = (float) SAMPLE_RATE / FFT_SIZE;

    private static final float PEAK_FALLOFF = 0.9995f;
    private static final float SPECTRUM_GAIN = 4f;
    private static final float EPSILON = 0.000001f;
    private static final long MIN_SEND_INTERVAL_MS = 16L;
    private static final long PROJECTION_SETTLE_DELAY_MS = 500L;

    private static volatile boolean sIsRunning = false;

    private final IBinder mBinder = new LocalBinder();
    private final Object mCaptureLock = new Object();
    private final MediaProjection.Callback mProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.d(TAG, "MediaProjection stopped externally");
            stopCapture();
            stopSelf();
        }
    };
    private final GlyphManager.Callback mGlyphCallback = new GlyphManager.Callback() {
        @Override
        public void onServiceConnected(ComponentName componentName) {
            if (mGM == null) {
                return;
            }

            Log.d(TAG, "Glyph service connected");
            if (Common.is22111()) {
                mGM.register(com.nothing.ketchum.Glyph.DEVICE_22111);
            } else if (Common.is20111()) {
                mGM.register(com.nothing.ketchum.Glyph.DEVICE_20111);
            } else if (Common.is23111()) {
                mGM.register(com.nothing.ketchum.Glyph.DEVICE_23111);
            } else if (Common.is23113()) {
                mGM.register(com.nothing.ketchum.Glyph.DEVICE_23113);
            } else if (Common.is24111()) {
                mGM.register(com.nothing.ketchum.Glyph.DEVICE_24111);
            } else {
                mGM.register(com.nothing.ketchum.Glyph.DEVICE_25111);
            }

            try {
                if (!mSessionOpen) {
                    mGM.openSession();
                    mSessionOpen = true;
                }
            } catch (GlyphException e) {
                Log.e(TAG, "Failed to open Glyph session", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mSessionOpen = false;
        }
    };

    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;
    private AudioManager mAudioManager;

    private GlyphManager mGM;
    private volatile boolean mSessionOpen = false;

    private MediaProjection mProjection;
    private AudioRecord mAudioRecord;
    private ExecutorService mCaptureExecutor;
    private volatile boolean mCapturing = false;

    private volatile VisualizerConfig mVisualizerConfig;
    private String mPresetKey = DEFAULT_PRESET_KEY;
    private String mDetectedPhoneModel = PHONE_MODEL_UNKNOWN;
    private List<String> mAvailablePresetKeys = Collections.emptyList();
    private int mSelectedDevice = DeviceProfile.DEVICE_UNKNOWN;
    private volatile int mLatencyCompensationMs = 0;
    private volatile int mLatencySettingsVersion = 0;
    private volatile int mPresetConfigVersion = 0;
    private volatile float mGamma = DEFAULT_GAMMA;

    private volatile boolean mHapticEnabled = false;
    private volatile float mHapticMultiplier = 1.0f;
    private volatile float mHapticGamma = 2.0f;
    private volatile FrequencyRange mHapticRange = new FrequencyRange(60, 250);
    private float mHapticPeakTracker = EPSILON;
    private float mDecayedHapticState = 0f;
    private int mLastHapticAmplitude = -1;
    private long mLastHapticUpdateMs = 0;
    private Vibrator mVibrator;

    private float[] mCurrentLightState = new float[0];
    private float[] mZonePeaks = new float[0];
    private float[] mDecayedFrequencyState = new float[0];
    private int mLastHash = Integer.MIN_VALUE;
    private long mLastSendMs = 0L;

    private final AudioDeviceCallback mAudioDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            refreshLatencyForCurrentAudioRoute();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            refreshLatencyForCurrentAudioRoute();
        }
    };

    private record ZoneSpec(float lowHz, float highHz, float lowPercent, float highPercent) {
        boolean hasPercentSlice() {
            return !Float.isNaN(lowPercent) && !Float.isNaN(highPercent);
        }
    }

    private static final class FrequencyRange {
        final float lowHz;
        final float highHz;
        final int binLo;
        final int binHi;

        FrequencyRange(float lowHz, float highHz) {
            this.lowHz = lowHz;
            this.highHz = highHz;
            this.binLo = Math.max(0, (int) Math.ceil(lowHz / HZ_PER_BIN));
            this.binHi = Math.max(binLo, Math.min(FFT_SIZE / 2, (int) Math.floor(highHz / HZ_PER_BIN)));
        }
    }

    private record VisualizerConfig(
            String presetKey,
            String description,
            float decay,
            ZoneSpec[] zones,
            FrequencyRange[] uniqueRanges,
            int[][] zoneToRangeIndices
    ) {
    }

    private record PendingFrame(float[] uniquePeaks, float hapticPeak, VisualizerConfig config, int configVersion, long dueAtMs) {
    }

    public static final class PresetInfo {
        public final String key;
        public final String description;

        PresetInfo(String key, String description) {
            this.key = key;
            this.description = description;
        }
    }

    public class LocalBinder extends Binder {
        public AudioCaptureService getService() {
            return AudioCaptureService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mWorkerThread = new HandlerThread("GlyphVizWorker", Process.THREAD_PRIORITY_BACKGROUND);
        mWorkerThread.start();
        mWorkerHandler = Handler.createAsync(mWorkerThread.getLooper());
        mAudioManager = getSystemService(AudioManager.class);
        if (mAudioManager != null) {
            mAudioManager.registerAudioDeviceCallback(mAudioDeviceCallback, mWorkerHandler);
        }

        mVibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        mSelectedDevice = DeviceProfile.detectDevice();
        mLatencyCompensationMs = loadLatencyCompensationMs(this, mSelectedDevice);
        mGamma = loadGamma(this);
        refreshLatencyForCurrentAudioRoute();

        try {
            refreshPresetCatalog();
            if (!mAvailablePresetKeys.isEmpty()) {
                mPresetKey = chooseDefaultPresetKey(phoneModelForDevice(mSelectedDevice), mAvailablePresetKeys);
                mVisualizerConfig = loadVisualizerConfig(mPresetKey);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load zones.config", e);
            mVisualizerConfig = null;
        }
        resetVisualizerState();

        mGM = GlyphManager.getInstance(getApplicationContext());
        mGM.init(mGlyphCallback);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String requestedPreset = intent != null ? intent.getStringExtra(EXTRA_PRESET_KEY) : null;
        if (requestedPreset != null && !requestedPreset.isBlank()) {
            setPreset(requestedPreset.trim());
        }

        startForeground(NOTIF_ID, buildNotification());
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        clearGlyphSession();
        if (mGM != null) {
            mGM.unInit();
            mGM = null;
        }
        if (mAudioManager != null) {
            mAudioManager.unregisterAudioDeviceCallback(mAudioDeviceCallback);
            mAudioManager = null;
        }
        if (mWorkerThread != null) {
            mWorkerThread.quitSafely();
            mWorkerThread = null;
            mWorkerHandler = null;
        }
        super.onDestroy();
    }

    public static boolean isRunning() {
        return sIsRunning;
    }

    public static int loadLatencyCompensationMs(Context context, int device) {
        return getPreferences(context).getInt(latencyPreferenceKey(device), 0);
    }

    public static int loadLatencyCompensationMs(Context context, int device, String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return loadLatencyCompensationMs(context, device);
        }

        SharedPreferences preferences = getPreferences(context);
        String preferenceKey = routeLatencyPreferenceKey(device, routeKey);
        if (preferences.contains(preferenceKey)) {
            return preferences.getInt(preferenceKey, 0);
        }
        return loadLatencyCompensationMs(context, device);
    }

    public static void saveLatencyCompensationMs(Context context, int device, int latencyMs) {
        getPreferences(context)
                .edit()
                .putInt(latencyPreferenceKey(device), latencyMs)
                .apply();
    }

    public static void saveLatencyCompensationMs(Context context, int device, String routeKey, int latencyMs) {
        if (routeKey == null || routeKey.isBlank()) {
            saveLatencyCompensationMs(context, device, latencyMs);
            return;
        }

        getPreferences(context)
                .edit()
                .putInt(routeLatencyPreferenceKey(device, routeKey), latencyMs)
                .apply();
    }

    public static float loadGamma(Context context) {
        return getPreferences(context).getFloat(PREF_GAMMA, DEFAULT_GAMMA);
    }

    public static void saveGamma(Context context, float gamma) {
        getPreferences(context)
                .edit()
                .putFloat(PREF_GAMMA, gamma)
                .apply();
    }

    public static List<Integer> loadLatencyPresets(Context context) {
        String saved = getPreferences(context).getString(PREF_LATENCY_PRESETS, null);
        if (saved == null || saved.isEmpty()) {
            return new ArrayList<>(Arrays.asList(10, 154, 300));
        }

        ArrayList<Integer> presets = new ArrayList<>();
        try {
            for (String part : saved.split(",")) {
                presets.add(Integer.parseInt(part.trim()));
            }
        } catch (Exception e) {
            return new ArrayList<>(Arrays.asList(10, 154, 300));
        }
        return presets;
    }

    public static void saveLatencyPresets(Context context, List<Integer> presets) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < presets.size(); i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append(presets.get(i));
        }
        getPreferences(context)
                .edit()
                .putString(PREF_LATENCY_PRESETS, builder.toString())
                .apply();
    }

    public static List<PresetInfo> loadPresetInfos(Context context, int device) {
        String detectedPhoneModel = detectPhoneModel();
        String selectedPhoneModel = phoneModelForDevice(device);
        String phoneModelForCatalog = PHONE_MODEL_UNKNOWN.equals(selectedPhoneModel)
                ? detectedPhoneModel
                : selectedPhoneModel;

        try {
            JSONObject root = loadZonesConfigRoot(context);
            List<String> matching = getPresetKeysForPhoneModel(root, phoneModelForCatalog);
            if (matching.isEmpty() && !PHONE_MODEL_UNKNOWN.equals(detectedPhoneModel)) {
                matching = getPresetKeysForPhoneModel(root, detectedPhoneModel);
            }
            if (matching.isEmpty()) {
                matching = getAllPresetKeys(root);
            }
            return buildPresetInfos(root, matching);
        } catch (FileNotFoundException e) {
            Log.w(TAG, "zones.config missing while loading preset list");
            return Collections.emptyList();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load preset list", e);
            return Collections.emptyList();
        }
    }

    public void setPreset(String presetSelection) {
        if (presetSelection == null || presetSelection.isBlank()) {
            return;
        }
        applyPresetSelection(presetSelection.trim());
    }

    public void setDevice(int device) {
        mSelectedDevice = device;
        setLatencyCompensationMs(loadLatencyCompensationMs(this, device));
        try {
            refreshPresetCatalog();
            if (!mAvailablePresetKeys.isEmpty() && !mAvailablePresetKeys.contains(mPresetKey)) {
                applyPresetSelection(chooseDefaultPresetKey(phoneModelForDevice(device), mAvailablePresetKeys));
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to refresh presets after device change", e);
        }
    }

    public void setLatencyCompensationMs(int latencyMs) {
        if (mLatencyCompensationMs != latencyMs) {
            mLatencyCompensationMs = latencyMs;
            mLatencySettingsVersion++;
        }
    }

    public void setGamma(float gamma) {
        mGamma = gamma;
    }

    public void setHapticEnabled(boolean enabled) {
        mHapticEnabled = enabled;
    }

    public void setHapticFreqRange(float minHz, float maxHz) {
        mHapticRange = new FrequencyRange(minHz, maxHz);
    }

    public void setHapticMultiplier(float multiplier) {
        mHapticMultiplier = multiplier;
    }

    public void setHapticGamma(float gamma) {
        mHapticGamma = gamma;
    }

    public void startCapture(int resultCode, Intent data) {
        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager is unavailable");
            sIsRunning = false;
            return;
        }

        synchronized (mCaptureLock) {
            stopCaptureLocked();

            // 1. MUST promote to foreground BEFORE validating the projection token
            startForeground(NOTIF_ID, buildNotification());

            MediaProjection projection = projectionManager.getMediaProjection(resultCode, data);
            if (projection == null) {
                Log.e(TAG, "MediaProjection token was denied or expired");
                stopForeground(STOP_FOREGROUND_REMOVE);
                sIsRunning = false;
                return;
            }

            mProjection = projection;
            if (mWorkerHandler != null) {
                mProjection.registerCallback(mProjectionCallback, mWorkerHandler);
            }

            mCapturing = true;
            sIsRunning = true;
            ensureCaptureExecutor();

            mCaptureExecutor.execute(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                try {
                    // Give the system a moment to settle the foreground state
                    SystemClock.sleep(PROJECTION_SETTLE_DELAY_MS);

                    AudioPlaybackCaptureConfiguration config =
                            new AudioPlaybackCaptureConfiguration.Builder(mProjection)
                                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                    .build();

                    // 2. Calculate safe buffer size
                    int minBufSize = AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);

                    int bufferSize = Math.max(minBufSize, FFT_SIZE * 4);

                    AudioRecord localRecord = new AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(config)
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build())
                            .setBufferSizeInBytes(bufferSize)
                            .build();

                    // 3. Verify Initialization
                    if (localRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        localRecord.release();
                        throw new IllegalStateException("AudioRecord failed to initialize. Check if another app is monopolizing audio.");
                    }

                    synchronized (mCaptureLock) {
                        if (!mCapturing || mProjection != projection) {
                            localRecord.release();
                            return;
                        }
                        mAudioRecord = localRecord;
                    }

                    localRecord.startRecording();
                    runCaptureLoop(localRecord);

                } catch (Exception e) {
                    Log.e(TAG, "Audio capture failed", e);
                    if (mWorkerHandler != null) {
                        mWorkerHandler.post(this::stopSelf);
                    }
                } finally {
                    synchronized (mCaptureLock) {
                        releaseAudioRecord();
                    }
                }
            });
        }

        refreshNotification();
        requestTileRefresh();
    }
    public void stopCapture() {
        synchronized (mCaptureLock) {
            stopCaptureLocked();
        }
    }

    private void stopCaptureLocked() {
        mCapturing = false;
        sIsRunning = false;
        shutdownCaptureExecutor();
        releaseAudioRecord();
        releaseProjection();
        turnOffGlyphs();
        resetVisualizerState();
        stopForeground(STOP_FOREGROUND_REMOVE);
        requestTileRefresh();
    }

    private void ensureCaptureExecutor() {
        if (mCaptureExecutor != null && !mCaptureExecutor.isShutdown()) {
            return;
        }
        mCaptureExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "GlyphVizCapture");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void shutdownCaptureExecutor() {
        if (mCaptureExecutor != null) {
            mCaptureExecutor.shutdownNow();
            mCaptureExecutor = null;
        }
    }

    private void runCaptureLoop(AudioRecord record) {
        VisualizerConfig initialConfig = mVisualizerConfig;
        if (initialConfig == null) {
            return;
        }

        float[] hann = buildHannWindow();
        float[] ring = new float[ANALYSIS_WINDOW];
        short[] hop = new short[HOP];
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        float[] magnitude = new float[(FFT_SIZE / 2) + 1];
        ArrayDeque<PendingFrame> pendingFrames = new ArrayDeque<>();

        int appliedLatencyVersion = mLatencySettingsVersion;
        int appliedPresetVersion = mPresetConfigVersion;
        int ringPosition = 0;
        int filled = 0;

        while (mCapturing && !Thread.currentThread().isInterrupted()) {
            VisualizerConfig config = mVisualizerConfig;
            int presetVersion = mPresetConfigVersion;
            if (config == null) {
                return;
            }

            int read = record.read(hop, 0, HOP, AudioRecord.READ_BLOCKING);
            if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                Log.e(TAG, "AudioRecord died while capturing");
                return;
            }
            if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                Log.w(TAG, "AudioRecord read returned " + read);
                continue;
            }
            if (read <= 0) {
                continue;
            }

            for (int i = 0; i < read; i++) {
                ring[ringPosition] = hop[i] / 32768f;
                ringPosition = (ringPosition + 1) % ANALYSIS_WINDOW;
            }
            filled = Math.min(filled + read, ANALYSIS_WINDOW);
            if (filled < ANALYSIS_WINDOW) {
                continue;
            }

            Arrays.fill(re, 0f);
            Arrays.fill(im, 0f);
            for (int i = 0; i < ANALYSIS_WINDOW; i++) {
                re[i] = ring[(ringPosition + i) % ANALYSIS_WINDOW] * hann[i];
            }

            fft(re, im);
            for (int i = 0; i <= FFT_SIZE / 2; i++) {
                magnitude[i] = (float) Math.hypot(re[i], im[i]);
            }

            if (presetVersion != mPresetConfigVersion || config != mVisualizerConfig) {
                pendingFrames.clear();
                appliedPresetVersion = mPresetConfigVersion;
                continue;
            }

            if (appliedLatencyVersion != mLatencySettingsVersion || appliedPresetVersion != presetVersion) {
                pendingFrames.clear();
                appliedLatencyVersion = mLatencySettingsVersion;
                appliedPresetVersion = presetVersion;
            }

            float[] uniquePeaks = computeUniquePeaks(config, magnitude);

            float hapticPeak = 0f;
            FrequencyRange hRange = mHapticRange;
            if (mHapticEnabled && hRange != null) {
                for (int bin = hRange.binLo; bin <= hRange.binHi; bin++) {
                    if (magnitude[bin] > hapticPeak) {
                        hapticPeak = magnitude[bin];
                    }
                }
            }

            pendingFrames.addLast(new PendingFrame(
                    uniquePeaks,
                    hapticPeak,
                    config,
                    presetVersion,
                    SystemClock.elapsedRealtime() + mLatencyCompensationMs
            ));
            dispatchDueFrames(pendingFrames);
        }
    }

    private float[] computeUniquePeaks(VisualizerConfig config, float[] magnitude) {
        float[] uniquePeaks = new float[config.uniqueRanges.length];
        for (int i = 0; i < config.uniqueRanges.length; i++) {
            FrequencyRange range = config.uniqueRanges[i];
            float peak = 0f;
            for (int bin = range.binLo; bin <= range.binHi; bin++) {
                if (magnitude[bin] > peak) {
                    peak = magnitude[bin];
                }
            }
            uniquePeaks[i] = peak;
        }
        return uniquePeaks;
    }

    private void dispatchDueFrames(ArrayDeque<PendingFrame> pendingFrames) {
        long nowMs = SystemClock.elapsedRealtime();
        while (!pendingFrames.isEmpty()) {
            PendingFrame pendingFrame = pendingFrames.peekFirst();
            if (pendingFrame == null || pendingFrame.dueAtMs > nowMs) {
                return;
            }
            pendingFrames.removeFirst();
            processFrame(pendingFrame.uniquePeaks, pendingFrame.hapticPeak, pendingFrame.config, pendingFrame.configVersion);
        }
    }

    private void processFrame(float[] uniquePeaks, float hapticPeak, VisualizerConfig config, int configVersion) {
        if (!mSessionOpen || mGM == null || config == null || configVersion != mPresetConfigVersion) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - mLastSendMs < MIN_SEND_INTERVAL_MS) {
            return;
        }

        if (mHapticEnabled && hapticPeak > 0) {
            performHapticFeedback(hapticPeak);
        }

        ensureStateArrays(config.zones.length, config.uniqueRanges.length);

        float[] nextLightState = computeNextLightState(uniquePeaks, config);
        System.arraycopy(nextLightState, 0, mCurrentLightState, 0, nextLightState.length);

        int[] frameColors = buildFrameColors(nextLightState, config.zones.length);
        int frameHash = Arrays.hashCode(frameColors);
        if (frameHash == mLastHash) {
            return;
        }

        try {
            mGM.setFrameColors(frameColors);
            mLastHash = frameHash;
            mLastSendMs = now;
        } catch (Exception e) {
            Log.w(TAG, "Failed to push frame colors", e);
        }
    }

    private int[] buildFrameColors(float[] normalizedLightState, int expectedLength) {
        int[] frameColors = new int[expectedLength];
        int count = Math.min(normalizedLightState.length, expectedLength);
        for (int i = 0; i < count; i++) {
            frameColors[i] = Math.round(applyGamma(normalizedLightState[i]) * 4095f);
        }
        return frameColors;
    }

    private float applyGamma(float normalizedValue) {
        if (normalizedValue <= 0f) {
            return 0f;
        }
        return (float) Math.pow(normalizedValue, mGamma);
    }

    private void performHapticFeedback(float rawPeak) {
        if (mVibrator == null || !mVibrator.hasVibrator()) return;

        float current = rawPeak * SPECTRUM_GAIN * mHapticMultiplier;

        // Smooth the input slightly to remove high-frequency noise/jitter
        mDecayedHapticState = (0.8f * mDecayedHapticState) + (0.2f * current);

        // Track peaks for auto-normalization
        mHapticPeakTracker = Math.max(mDecayedHapticState, mHapticPeakTracker * 0.998f);
        if (mHapticPeakTracker < EPSILON) return;

        float normalized = Math.min(1f, mDecayedHapticState / mHapticPeakTracker);
        float shaped = (float) Math.pow(normalized, mHapticGamma);

        int amplitude = Math.round(shaped * 255f);
        amplitude = Math.max(0, Math.min(255, amplitude));

        // Deadzone to prevent the motor from "buzzing" at very low levels
        if (amplitude < 15) amplitude = 0;

        long now = SystemClock.elapsedRealtime();

        // LOGIC: To prevent "tapping", we avoid restarting the motor unless necessary.
        // We only call vibrate if:
        // 1. The amplitude has changed significantly (avoiding micro-jitter restarts)
        // 2. The previous vibration is about to expire (keep-alive)
        // 3. We are transitioning from/to silence.

        boolean significantChange = Math.abs(amplitude - mLastHapticAmplitude) > 35;
        boolean transition = (amplitude == 0) != (mLastHapticAmplitude == 0);
        boolean timeout = (now - mLastHapticUpdateMs) > 100;

        if (significantChange || transition || (amplitude > 0 && timeout)) {
            if (amplitude > 0) {
                // Duration of 150ms is long enough to feel continuous if we update regularly.
                // Modern Android restarts the effect on every call, which is why we limit calls.
                mVibrator.vibrate(VibrationEffect.createOneShot(150, amplitude));
                mLastHapticUpdateMs = now;
            } else {
                mVibrator.cancel();
            }
            mLastHapticAmplitude = amplitude;
        }
    }

    private float[] computeNextLightState(float[] uniquePeaks, VisualizerConfig config) {
        float[] decayedFrequencyState = computeDecayedFrequencyState(uniquePeaks, config);
        float[] nextState = new float[config.zones.length];

        for (int zoneIndex = 0; zoneIndex < config.zones.length; zoneIndex++) {
            float rawZonePeak = 0f;
            int[] overlappingRanges = config.zoneToRangeIndices[zoneIndex];
            for (int rangeIndex : overlappingRanges) {
                if (rangeIndex >= 0 && rangeIndex < decayedFrequencyState.length) {
                    rawZonePeak = Math.max(rawZonePeak, decayedFrequencyState[rangeIndex]);
                }
            }

            mZonePeaks[zoneIndex] = Math.max(rawZonePeak, mZonePeaks[zoneIndex] * PEAK_FALLOFF);
            if (mZonePeaks[zoneIndex] < EPSILON) {
                mZonePeaks[zoneIndex] = EPSILON;
            }

            float normalized = rawZonePeak / mZonePeaks[zoneIndex];
            float shaped = normalized * normalized;
            float mapped = applyPercentSlice(shaped, config.zones[zoneIndex]);
            nextState[zoneIndex] = mapped < EPSILON ? 0f : mapped;
        }

        return nextState;
    }

    private float[] computeDecayedFrequencyState(float[] uniquePeaks, VisualizerConfig config) {
        float[] next = new float[mDecayedFrequencyState.length];
        for (int i = 0; i < next.length; i++) {
            float current = (i < uniquePeaks.length ? uniquePeaks[i] : 0f) * SPECTRUM_GAIN;
            float risen = Math.max(mDecayedFrequencyState[i], current);
            float decayed = (config.decay * risen) + ((1f - config.decay) * current);
            next[i] = decayed < EPSILON ? 0f : decayed;
        }
        System.arraycopy(next, 0, mDecayedFrequencyState, 0, next.length);
        return next;
    }

    private void ensureStateArrays(int zoneCount, int uniqueRangeCount) {
        if (mCurrentLightState.length == zoneCount
                && mZonePeaks.length == zoneCount
                && mDecayedFrequencyState.length == uniqueRangeCount) {
            return;
        }

        mCurrentLightState = new float[zoneCount];
        mZonePeaks = new float[zoneCount];
        Arrays.fill(mZonePeaks, EPSILON);
        mDecayedFrequencyState = new float[uniqueRangeCount];
        mLastHash = Integer.MIN_VALUE;
    }

    private void resetVisualizerState() {
        mDecayedHapticState = 0f;
        mLastHapticAmplitude = -1;
        if (mVisualizerConfig == null) {
            mCurrentLightState = new float[0];
            mZonePeaks = new float[0];
            mDecayedFrequencyState = new float[0];
        } else {
            mCurrentLightState = new float[mVisualizerConfig.zones.length];
            mZonePeaks = new float[mVisualizerConfig.zones.length];
            Arrays.fill(mZonePeaks, EPSILON);
            mDecayedFrequencyState = new float[mVisualizerConfig.uniqueRanges.length];
        }
        mLastHash = Integer.MIN_VALUE;
        mLastSendMs = 0L;
    }

    private static float applyPercentSlice(float normalizedValue, ZoneSpec zone) {
        if (!zone.hasPercentSlice()) {
            return normalizedValue;
        }

        float low = Math.min(zone.lowPercent, zone.highPercent);
        float high = Math.max(zone.lowPercent, zone.highPercent);
        float percent = normalizedValue * 100f;

        if (percent <= low) {
            return 0f;
        }
        if (percent >= high || high == low) {
            return 1f;
        }
        return (percent - low) / (high - low);
    }

    private void applyPresetSelection(String presetSelection) {
        try {
            refreshPresetCatalog();
            String resolvedPresetKey = resolvePresetKey(presetSelection, mAvailablePresetKeys);
            if (!resolvedPresetKey.equals(mPresetKey) || mVisualizerConfig == null) {
                mVisualizerConfig = loadVisualizerConfig(resolvedPresetKey);
                mPresetKey = resolvedPresetKey;
                mPresetConfigVersion++;
                resetVisualizerState();
                refreshNotification();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply preset: " + presetSelection, e);
            mVisualizerConfig = null;
            resetVisualizerState();
            refreshNotification();
        }
    }

    private String resolvePresetKey(String presetSelection, List<String> availablePresetKeys) {
        if (availablePresetKeys == null || availablePresetKeys.isEmpty()) {
            return DEFAULT_PRESET_KEY;
        }
        if (availablePresetKeys.contains(presetSelection)) {
            return presetSelection;
        }

        String preferred = chooseDefaultPresetKey(phoneModelForDevice(mSelectedDevice), availablePresetKeys);
        if (availablePresetKeys.contains(preferred)) {
            return preferred;
        }

        return availablePresetKeys.get(0);
    }

    private VisualizerConfig loadVisualizerConfig(String presetKey) throws IOException, JSONException {
        JSONObject root = loadZonesConfigRoot(this);
        JSONObject preset = root.optJSONObject(presetKey);
        if (preset == null) {
            throw new JSONException("Preset '" + presetKey + "' not found");
        }

        JSONArray zonesArray = preset.optJSONArray("zones");
        if (zonesArray == null || zonesArray.length() == 0) {
            throw new JSONException("Preset '" + presetKey + "' has no zones");
        }

        double decayAlpha = preset.has("decay-alpha")
                ? preset.optDouble("decay-alpha", 0.8)
                : root.optDouble("decay-alpha", 0.8);

        ZoneSpec[] zones = parseZoneSpecs(zonesArray);
        return buildVisualizerConfig(
                presetKey,
                preset.optString("description", presetKey),
                decayAlpha,
                zones
        );
    }

    private VisualizerConfig buildVisualizerConfig(
            String presetKey,
            String description,
            double decayAlpha,
            ZoneSpec[] zones
    ) {
        float adjustedDecay = 0.86f + ((float) decayAlpha / 10f);
        List<float[]> uniquePairs = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();

        for (ZoneSpec zone : zones) {
            String key = String.format(Locale.US, "%.4f|%.4f", zone.lowHz, zone.highHz);
            if (seenPairs.add(key)) {
                uniquePairs.add(new float[]{zone.lowHz, zone.highHz});
            }
        }

        uniquePairs.sort((left, right) -> {
            int lowCompare = Float.compare(left[0], right[0]);
            return lowCompare != 0 ? lowCompare : Float.compare(left[1], right[1]);
        });

        FrequencyRange[] uniqueRanges = new FrequencyRange[uniquePairs.size()];
        for (int i = 0; i < uniquePairs.size(); i++) {
            float[] pair = uniquePairs.get(i);
            uniqueRanges[i] = new FrequencyRange(pair[0], pair[1]);
        }

        int[][] zoneToRangeIndices = new int[zones.length][];
        for (int zoneIndex = 0; zoneIndex < zones.length; zoneIndex++) {
            ZoneSpec zone = zones[zoneIndex];
            ArrayList<Integer> overlaps = new ArrayList<>();
            for (int rangeIndex = 0; rangeIndex < uniqueRanges.length; rangeIndex++) {
                FrequencyRange range = uniqueRanges[rangeIndex];
                if (!(range.highHz < zone.lowHz || range.lowHz > zone.highHz)) {
                    overlaps.add(rangeIndex);
                }
            }

            int[] mapping = new int[overlaps.size()];
            for (int i = 0; i < overlaps.size(); i++) {
                mapping[i] = overlaps.get(i);
            }
            zoneToRangeIndices[zoneIndex] = mapping;
        }

        return new VisualizerConfig(
                presetKey,
                description,
                adjustedDecay,
                zones,
                uniqueRanges,
                zoneToRangeIndices
        );
    }

    private ZoneSpec[] parseZoneSpecs(JSONArray zonesArray) throws JSONException {
        ZoneSpec[] zones = new ZoneSpec[zonesArray.length()];
        for (int i = 0; i < zonesArray.length(); i++) {
            JSONArray zoneArray = zonesArray.getJSONArray(i);
            float lowHz = (float) zoneArray.getDouble(0);
            float highHz = (float) zoneArray.getDouble(1);
            if (lowHz > highHz) {
                float tmp = lowHz;
                lowHz = highHz;
                highHz = tmp;
            }

            zones[i] = new ZoneSpec(
                    lowHz,
                    highHz,
                    parseOptionalPercent(zoneArray, 3),
                    parseOptionalPercent(zoneArray, 4)
            );
        }
        return zones;
    }

    private void releaseAudioRecord() {
        if (mAudioRecord == null) {
            return;
        }

        try {
            mAudioRecord.stop();
        } catch (Exception ignored) {
        }
        mAudioRecord.release();
        mAudioRecord = null;
    }

    private void releaseProjection() {
        if (mProjection == null) {
            return;
        }

        try {
            mProjection.unregisterCallback(mProjectionCallback);
        } catch (Exception ignored) {
        }
        try {
            mProjection.stop();
        } catch (Exception ignored) {
        }
        mProjection = null;
    }

    private void turnOffGlyphs() {
        if (mGM == null || !mSessionOpen) {
            return;
        }

        int glyphCount = resolveGlyphCount();
        if (glyphCount > 0) {
            try {
                mGM.setFrameColors(new int[glyphCount]);
            } catch (Exception e) {
                Log.w(TAG, "Failed to clear glyph frame", e);
            }
        }

        try {
            mGM.turnOff();
        } catch (Exception e) {
            Log.w(TAG, "Failed to turn glyphs off", e);
        }
    }

    private void clearGlyphSession() {
        turnOffGlyphs();
        if (mGM != null && mSessionOpen) {
            try {
                mGM.closeSession();
            } catch (GlyphException e) {
                Log.w(TAG, "Failed to close Glyph session", e);
            }
            mSessionOpen = false;
        }
    }

    private int resolveGlyphCount() {
        if (mVisualizerConfig != null) {
            return mVisualizerConfig.zones.length;
        }
        return switch (mSelectedDevice) {
            case DeviceProfile.DEVICE_NP1 -> 15;
            case DeviceProfile.DEVICE_NP2 -> 33;
            case DeviceProfile.DEVICE_NP2A -> 26;
            case DeviceProfile.DEVICE_NP3A -> 36;
            case DeviceProfile.DEVICE_NP4A -> 7;
            default -> 0;
        };
    }

    private Notification buildNotification() {
        ensureNotificationChannel();

        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        PendingIntent stopIntent = PendingIntent.getService(
                this,
                1,
                new Intent(this, AudioCaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String content = mVisualizerConfig == null
                ? "zones.config missing"
                : mDetectedPhoneModel + " • " + mVisualizerConfig.presetKey + " • " + mVisualizerConfig.description;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Glyph Visualizer")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void ensureNotificationChannel() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null || notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Glyph Visualizer",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps the visualizer alive while audio capture is active");
        notificationManager.createNotificationChannel(channel);
    }

    private void refreshNotification() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.notify(NOTIF_ID, buildNotification());
        }
    }

    private void requestTileRefresh() {
        TileService.requestListeningState(
                this,
                new ComponentName(this, VisualizerTileService.class)
        );
    }

    private void refreshPresetCatalog() throws IOException, JSONException {
        mDetectedPhoneModel = detectPhoneModel();
        String selectedPhoneModel = phoneModelForDevice(mSelectedDevice);
        String phoneModelForCatalog = PHONE_MODEL_UNKNOWN.equals(selectedPhoneModel)
                ? mDetectedPhoneModel
                : selectedPhoneModel;

        JSONObject root = loadZonesConfigRoot(this);
        List<String> matching = getPresetKeysForPhoneModel(root, phoneModelForCatalog);
        if (matching.isEmpty() && !PHONE_MODEL_UNKNOWN.equals(mDetectedPhoneModel)) {
            matching = getPresetKeysForPhoneModel(root, mDetectedPhoneModel);
        }
        if (matching.isEmpty()) {
            matching = getAllPresetKeys(root);
        }
        mAvailablePresetKeys = matching;
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String latencyPreferenceKey(int device) {
        return PREF_LATENCY_PREFIX + Math.max(DeviceProfile.DEVICE_UNKNOWN, device);
    }

    private static String routeLatencyPreferenceKey(int device, String routeKey) {
        String sanitizedRouteKey = routeKey
                .trim()
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return PREF_LATENCY_ROUTE_PREFIX
                + Math.max(DeviceProfile.DEVICE_UNKNOWN, device)
                + "_"
                + sanitizedRouteKey;
    }

    private static JSONObject loadZonesConfigRoot(Context context) throws IOException, JSONException {
        return new JSONObject(loadZonesConfigText(context));
    }

    private static String loadZonesConfigText(Context context) throws IOException {
        InputStream inputStream = null;
        try {
            inputStream = context.getAssets().open("zones.config");
            return readFully(inputStream);
        } catch (IOException ignored) {
        } finally {
            closeQuietly(inputStream);
        }

        File externalDir = context.getExternalFilesDir(null);
        File[] candidates = new File[]{
                new File(context.getFilesDir(), "zones.config"),
                externalDir == null ? null : new File(externalDir, "zones.config"),
                new File(context.getApplicationInfo().dataDir, "zones.config")
        };

        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile()) {
                return readFile(candidate);
            }
        }

        throw new FileNotFoundException("zones.config not found");
    }

    private static String readFile(File file) throws IOException {
        FileInputStream inputStream = new FileInputStream(file);
        try {
            return readFully(inputStream);
        } finally {
            closeQuietly(inputStream);
        }
    }

    private static String readFully(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static List<String> getAllPresetKeys(JSONObject root) {
        ArrayList<String> presets = new ArrayList<>();
        JSONArray names = root.names();
        if (names == null) {
            return presets;
        }

        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            if (isPresetEntry(root, key)) {
                presets.add(key);
            }
        }
        Collections.sort(presets);
        return presets;
    }

    private static List<PresetInfo> buildPresetInfos(JSONObject root, List<String> keys) {
        ArrayList<PresetInfo> presets = new ArrayList<>();
        for (String key : keys) {
            JSONObject preset = root.optJSONObject(key);
            if (preset != null) {
                presets.add(new PresetInfo(key, preset.optString("description", key)));
            }
        }
        return presets;
    }

    private static List<String> getPresetKeysForPhoneModel(JSONObject root, String phoneModel) {
        ArrayList<String> presets = new ArrayList<>();
        if (PHONE_MODEL_UNKNOWN.equals(phoneModel)) {
            return presets;
        }

        JSONArray names = root.names();
        if (names == null) {
            return presets;
        }

        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            if (!isPresetEntry(root, key)) {
                continue;
            }
            JSONObject preset = root.optJSONObject(key);
            if (preset != null && phoneModel.equalsIgnoreCase(preset.optString("phone_model", ""))) {
                presets.add(key);
            }
        }
        Collections.sort(presets);
        return presets;
    }

    private static boolean isPresetEntry(JSONObject root, String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if ("version".equals(key)
                || "amp".equals(key)
                || "decay-alpha".equals(key)
                || "decay_alpha".equals(key)
                || "what-is-decay-alpha".equals(key)
                || "what-is-decay".equals(key)) {
            return false;
        }

        JSONObject preset = root.optJSONObject(key);
        return preset != null && preset.optJSONArray("zones") != null;
    }

    private static String chooseDefaultPresetKey(String phoneModel, List<String> presetKeys) {
        if (presetKeys == null || presetKeys.isEmpty()) {
            return DEFAULT_PRESET_KEY;
        }

        List<String> preferredKeys = switch (phoneModel) {
            case PHONE_MODEL_PHONE1 -> Arrays.asList("np1s", "np1");
            case PHONE_MODEL_PHONE2 -> Collections.singletonList("np2");
            case PHONE_MODEL_PHONE2A -> Collections.singletonList("np2a");
            case PHONE_MODEL_PHONE3A -> Arrays.asList("np3as", "np3a");
            case PHONE_MODEL_PHONE3 -> Collections.singletonList("np3test");
            case PHONE_MODEL_PHONE4A -> Collections.singletonList("np4a");
            default -> Collections.emptyList();
        };

        for (String preferredKey : preferredKeys) {
            if (presetKeys.contains(preferredKey)) {
                return preferredKey;
            }
        }
        return presetKeys.get(0);
    }

    private static String phoneModelForDevice(int device) {
        return switch (device) {
            case DeviceProfile.DEVICE_NP1 -> PHONE_MODEL_PHONE1;
            case DeviceProfile.DEVICE_NP2 -> PHONE_MODEL_PHONE2;
            case DeviceProfile.DEVICE_NP2A -> PHONE_MODEL_PHONE2A;
            case DeviceProfile.DEVICE_NP3A -> PHONE_MODEL_PHONE3A;
            case DeviceProfile.DEVICE_NP4A -> PHONE_MODEL_PHONE4A;
            default -> PHONE_MODEL_UNKNOWN;
        };
    }

    private static String detectPhoneModel() {
        if (Common.is20111()) {
            return PHONE_MODEL_PHONE1;
        }
        if (Common.is22111()) {
            return PHONE_MODEL_PHONE2;
        }
        if (Common.is23111() || Common.is23113()) {
            return PHONE_MODEL_PHONE2A;
        }
        if (Common.is24111()) {
            return PHONE_MODEL_PHONE3A;
        }
        if (Common.is25111()) {
            return PHONE_MODEL_PHONE4A;
        }

        String buildText = (
                Build.MANUFACTURER + " "
                        + Build.BRAND + " "
                        + Build.MODEL + " "
                        + Build.DEVICE + " "
                        + Build.PRODUCT
        ).toLowerCase(Locale.US);

        if (buildText.contains("phone 4a")) {
            return PHONE_MODEL_PHONE4A;
        }
        if (buildText.contains("phone 3a")) {
            return PHONE_MODEL_PHONE3A;
        }
        if (buildText.contains("phone 3")) {
            return PHONE_MODEL_PHONE3;
        }
        if (buildText.contains("phone 2a")) {
            return PHONE_MODEL_PHONE2A;
        }
        if (buildText.contains("phone 2")) {
            return PHONE_MODEL_PHONE2;
        }
        if (buildText.contains("phone 1")) {
            return PHONE_MODEL_PHONE1;
        }
        return PHONE_MODEL_UNKNOWN;
    }

    private static float parseOptionalPercent(JSONArray zoneArray, int index) {
        if (index >= zoneArray.length()) {
            return Float.NaN;
        }

        Object raw = zoneArray.opt(index);
        if (raw == null || raw == JSONObject.NULL) {
            return Float.NaN;
        }

        try {
            float value;
            if (raw instanceof Number number) {
                value = number.floatValue();
            } else {
                String text = String.valueOf(raw).trim();
                if (text.endsWith("%")) {
                    text = text.substring(0, text.length() - 1).trim();
                }
                value = Float.parseFloat(text);
            }

            if (value >= 0f && value <= 1f) {
                value *= 100f;
            }
            return value;
        } catch (Exception ignored) {
            return Float.NaN;
        }
    }

    private static float[] buildHannWindow() {
        float[] hann = new float[ANALYSIS_WINDOW];
        for (int i = 0; i < ANALYSIS_WINDOW; i++) {
            hann[i] = 0.5f * (1f - (float) Math.cos((2d * Math.PI * i) / ANALYSIS_WINDOW));
        }
        return hann;
    }

    private static void fft(float[] re, float[] im) {
        int j = 0;
        for (int i = 1; i < FFT_SIZE; i++) {
            int bit = FFT_SIZE >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                float reTmp = re[i];
                re[i] = re[j];
                re[j] = reTmp;

                float imTmp = im[i];
                im[i] = im[j];
                im[j] = imTmp;
            }
        }

        for (int len = 2; len <= FFT_SIZE; len <<= 1) {
            double angle = (-2d * Math.PI) / len;
            float wr = (float) Math.cos(angle);
            float wi = (float) Math.sin(angle);

            for (int i = 0; i < FFT_SIZE; i += len) {
                float cr = 1f;
                float ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    float ur = re[i + k];
                    float ui = im[i + k];
                    float vr = (re[i + k + (len / 2)] * cr) - (im[i + k + (len / 2)] * ci);
                    float vi = (re[i + k + (len / 2)] * ci) + (im[i + k + (len / 2)] * cr);

                    re[i + k] = ur + vr;
                    im[i + k] = ui + vi;
                    re[i + k + (len / 2)] = ur - vr;
                    im[i + k + (len / 2)] = ui - vi;

                    float nextCr = (cr * wr) - (ci * wi);
                    ci = (cr * wi) + (ci * wr);
                    cr = nextCr;
                }
            }
        }
    }

    public String getCurrentDeviceName() {
        AudioRouteInfo routeInfo = resolveCurrentAudioRoute();
        return routeInfo != null ? routeInfo.displayName : "Internal Speaker";
    }

    private void refreshLatencyForCurrentAudioRoute() {
        SharedPreferences appPreferences = getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE);
        if (!appPreferences.getBoolean("auto_device_enabled", true)) {
            return;
        }

        AudioRouteInfo routeInfo = resolveCurrentAudioRoute();
        String routeKey = routeInfo != null ? routeInfo.storageKey : null;
        setLatencyCompensationMs(loadLatencyCompensationMs(this, mSelectedDevice, routeKey));
    }

    private AudioRouteInfo resolveCurrentAudioRoute() {
        if (mAudioManager == null) {
            return null;
        }

        AudioDeviceInfo[] outputs = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo preferredOutput = null;
        for (AudioDeviceInfo device : outputs) {
            if (isBluetoothOutput(device)) {
                preferredOutput = device;
                break;
            }
        }
        if (preferredOutput == null) {
            for (AudioDeviceInfo device : outputs) {
                if (isWiredOutput(device)) {
                    preferredOutput = device;
                    break;
                }
            }
        }
        if (preferredOutput == null) {
            for (AudioDeviceInfo device : outputs) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    preferredOutput = device;
                    break;
                }
            }
        }
        if (preferredOutput == null && outputs.length > 0) {
            preferredOutput = outputs[0];
        }
        return preferredOutput != null ? toAudioRouteInfo(preferredOutput) : null;
    }

    private static boolean isBluetoothOutput(AudioDeviceInfo device) {
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST;
    }

    private static boolean isWiredOutput(AudioDeviceInfo device) {
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_HEADSET;
    }

    private static AudioRouteInfo toAudioRouteInfo(AudioDeviceInfo device) {
        String routeName = device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                ? "Internal Speaker"
                : String.valueOf(device.getProductName());
        String normalizedName = routeName.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalizedName.isEmpty()) {
            normalizedName = "unknown_output";
        }

        String address = device.getAddress();
        String normalizedAddress = null;
        if (address != null && !address.isBlank()) {
            normalizedAddress = address.toLowerCase(Locale.US)
                    .replaceAll("[^a-z0-9._-]+", "_")
                    .replaceAll("^_+|_+$", "");
        }

        String routeKey = device.getType() + "_" + (normalizedAddress != null && !normalizedAddress.isEmpty()
                ? normalizedAddress
                : normalizedName);
        return new AudioRouteInfo(routeKey, routeName);
    }

    private record AudioRouteInfo(String storageKey, String displayName) {
    }
}
