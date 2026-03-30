package com.glyphvisualizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.nothing.ketchum.Common;
import com.nothing.ketchum.GlyphException;
import com.nothing.ketchum.GlyphFrame;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real-time Nothing glyph visualizer driven by zones.config.
 *
 * The Java port now follows the same high-level pipeline as musicViz.py:
 *   FFT -> unique frequency peaks -> overlapping zone mapping ->
 *   quadratic normalization -> optional percent slice mapping -> glyph output
 *
 * In real time we cannot know the future max of the song, so normalization uses
 * a rolling per-zone peak while decay is applied from the current light state.
 * That makes each frame depend on the two inputs you asked for:
 *   1. the current FFT peaks
 *   2. the current light state (used as the decay source)
 */
public class AudioCaptureService extends Service {

    private static final String TAG = "GlyphViz:Service";
    private static final String CHANNEL_ID = "glyph_viz_channel";
    private static final int NOTIF_ID = 1;

    public static final String EXTRA_PRESET_KEY = "preset_key";
    private static final String DEFAULT_PRESET_KEY = "np2";
    private static final String PHONE_MODEL_UNKNOWN = "UNKNOWN";
    private static final String PHONE_MODEL_PHONE1 = "PHONE1";
    private static final String PHONE_MODEL_PHONE2 = "PHONE2";
    private static final String PHONE_MODEL_PHONE2A = "PHONE2A";
    private static final String PHONE_MODEL_PHONE3A = "PHONE3A";
    private static final String PHONE_MODEL_PHONE3 = "PHONE3";

    private static final int SAMPLE_RATE = 44100;
    private static final int FPS = 60;
    private static final int HOP = SAMPLE_RATE / FPS;
    private static final int WIN = 2048;
    private static final float HZ_PER_BIN = (float) SAMPLE_RATE / WIN;

    private static final float THRESHOLD = 0.06f;
    private static final float PEAK_FALLOFF = 0.9995f;
    private static final float EPSILON = 0.000001f;
    private static final long MIN_SEND_MS = 16L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final IBinder mBinder = new LocalBinder();

    private GlyphManager mGM;
    private boolean mSessionOpen = false;

    private MediaProjection mProjection;
    private AudioRecord mAudioRecord;
    private ExecutorService mExecutor;
    private volatile boolean mCapturing = false;

    private VisualizerConfig mVisualizerConfig;
    private String mPresetKey = DEFAULT_PRESET_KEY;
    private String mDetectedPhoneModel = PHONE_MODEL_UNKNOWN;
    private List<String> mAvailablePresetKeys = Collections.emptyList();

    private float[] mCurrentLightState = new float[0];
    private float[] mZonePeaks = new float[0];
    private int mLastHash = -999;
    private long mLastSendMs = 0L;

    private final GlyphManager.Callback mGlyphCallback = new GlyphManager.Callback() {
        @Override
        public void onServiceConnected(ComponentName cn) {
            Log.d(TAG, "Glyph connected");
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
                mGM.register(com.nothing.ketchum.Glyph.DEVICE_22111);
            }
            try {
                mGM.openSession();
                mSessionOpen = true;
                Log.d(TAG, "Session open");
            } catch (GlyphException e) {
                Log.e(TAG, "Session fail: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName cn) {
            mSessionOpen = false;
        }
    };

    public class LocalBinder extends Binder {
        public AudioCaptureService getService() {
            return AudioCaptureService.this;
        }
    }

    private static final class ZoneSpec {
        final float lowHz;
        final float highHz;
        final float lowPercent;
        final float highPercent;

        ZoneSpec(float lowHz, float highHz, float lowPercent, float highPercent) {
            this.lowHz = lowHz;
            this.highHz = highHz;
            this.lowPercent = lowPercent;
            this.highPercent = highPercent;
        }

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
            this.binHi = Math.max(binLo, Math.min(WIN / 2, (int) Math.floor(highHz / HZ_PER_BIN)));
        }
    }

    private static final class VisualizerConfig {
        final String presetKey;
        final String description;
        final float decay;
        final ZoneSpec[] zones;
        final FrequencyRange[] uniqueRanges;
        final int[][] zoneToRangeIndices;

        VisualizerConfig(
                String presetKey,
                String description,
                float decay,
                ZoneSpec[] zones,
                FrequencyRange[] uniqueRanges,
                int[][] zoneToRangeIndices
        ) {
            this.presetKey = presetKey;
            this.description = description;
            this.decay = decay;
            this.zones = zones;
            this.uniqueRanges = uniqueRanges;
            this.zoneToRangeIndices = zoneToRangeIndices;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            refreshPresetCatalog();
            if (mAvailablePresetKeys.isEmpty()) {
                throw new JSONException("No presets available in zones.config");
            }
            if (!mAvailablePresetKeys.contains(mPresetKey)) {
                mPresetKey = chooseDefaultPresetKey(mDetectedPhoneModel, mAvailablePresetKeys);
            }
            mVisualizerConfig = loadVisualizerConfig(mPresetKey);
            resetVisualizerState();
            Log.d(TAG, "Detected " + mDetectedPhoneModel + ", loaded preset " + mPresetKey);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load zones.config: " + e.getMessage(), e);
        }

        mGM = GlyphManager.getInstance(getApplicationContext());
        mGM.init(mGlyphCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String requestedPreset = intent != null ? intent.getStringExtra(EXTRA_PRESET_KEY) : null;
        if (requestedPreset != null && !requestedPreset.trim().isEmpty()) {
            requestedPreset = requestedPreset.trim();
            if (!requestedPreset.equals(mPresetKey)) {
                try {
                    refreshPresetCatalog();
                    if (!mAvailablePresetKeys.contains(requestedPreset)) {
                        throw new JSONException(
                                "Preset '" + requestedPreset + "' is not available for " + mDetectedPhoneModel
                        );
                    }
                    mVisualizerConfig = loadVisualizerConfig(requestedPreset);
                    mPresetKey = requestedPreset;
                    resetVisualizerState();
                    Log.d(TAG, "Switched preset to " + mPresetKey);
                } catch (Exception e) {
                    Log.e(TAG, "Preset switch failed: " + requestedPreset, e);
                }
            }
        }
        startForeground(NOTIF_ID, buildNotification());
        return START_NOT_STICKY;
    }

    public String getDetectedPhoneModel() {
        return mDetectedPhoneModel;
    }

    public List<String> getAvailablePresetKeys() {
        return new ArrayList<>(mAvailablePresetKeys);
    }

    @Override
    public void onDestroy() {
        stopCapture();
        if (mSessionOpen) {
            try {
                mGM.closeSession();
            } catch (GlyphException ignored) {
            }
        }
        if (mGM != null) {
            mGM.unInit();
        }
        super.onDestroy();
    }

    public void startCapture(int resultCode, Intent data) {
        if (mCapturing) {
            stopCapture();
        }
        if (mVisualizerConfig == null) {
            Log.e(TAG, "Cannot start capture without a parsed zones.config preset");
            return;
        }

        MediaProjectionManager pm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mProjection = pm.getMediaProjection(resultCode, data);
        if (mProjection == null) {
            Log.e(TAG, "Null projection");
            return;
        }
        mProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopCapture();
                stopSelf();
            }
        }, mHandler);

        mExecutor = Executors.newSingleThreadExecutor();
        mExecutor.submit(this::captureLoop);
        Log.d(TAG, "startCapture OK");
    }

    public void stopCapture() {
        mCapturing = false;
        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
            } catch (Exception ignored) {
            }
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if (mProjection != null) {
            mProjection.stop();
            mProjection = null;
        }
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
        resetVisualizerState();
        mHandler.post(() -> {
            if (mGM != null && mSessionOpen) {
                try {
                    mGM.turnOff();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void captureLoop() {
        final VisualizerConfig config = mVisualizerConfig;
        if (config == null) {
            Log.e(TAG, "captureLoop aborted: config missing");
            stopSelf();
            return;
        }

        AudioPlaybackCaptureConfiguration cfg =
                new AudioPlaybackCaptureConfiguration.Builder(mProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        mAudioRecord = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf, WIN * 4))
                .build();

        if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            stopSelf();
            return;
        }

        mAudioRecord.startRecording();
        mCapturing = true;
        Log.d(TAG, "Capture started using " + config.presetKey + " with " + config.zones.length + " zones");

        float[] hann = buildHannWindow(WIN);
        float[] ring = new float[WIN];
        short[] hop = new short[HOP];
        float[] re = new float[WIN];
        float[] im = new float[WIN];
        float[] mag = new float[(WIN / 2) + 1];
        int rPos = 0;
        int filled = 0;

        while (mCapturing) {
            int read = mAudioRecord.read(hop, 0, HOP);
            if (read <= 0) {
                continue;
            }

            for (int i = 0; i < read; i++) {
                ring[rPos] = hop[i] / 32768f;
                rPos = (rPos + 1) % WIN;
            }
            filled = Math.min(filled + read, WIN);
            if (filled < WIN) {
                continue;
            }

            for (int i = 0; i < WIN; i++) {
                re[i] = ring[(rPos + i) % WIN] * hann[i];
                im[i] = 0f;
            }
            fft(re, im, WIN);

            for (int k = 0; k <= WIN / 2; k++) {
                mag[k] = (float) Math.sqrt((re[k] * re[k]) + (im[k] * im[k]));
            }

            float[] uniquePeaks = new float[config.uniqueRanges.length];
            for (int fi = 0; fi < config.uniqueRanges.length; fi++) {
                FrequencyRange range = config.uniqueRanges[fi];
                float peak = 0f;
                for (int bin = range.binLo; bin <= range.binHi; bin++) {
                    if (mag[bin] > peak) {
                        peak = mag[bin];
                    }
                }
                uniquePeaks[fi] = peak;
            }

            final float[] peaksForFrame = uniquePeaks;
            mHandler.post(() -> processFrame(peaksForFrame));
        }
        Log.d(TAG, "Loop ended");
    }

    private void processFrame(float[] uniquePeaks) {
        VisualizerConfig config = mVisualizerConfig;
        if (!mSessionOpen || mGM == null || config == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - mLastSendMs < MIN_SEND_MS) {
            return;
        }
        mLastSendMs = now;

        ensureStateArrays(config.zones.length);

        float[] nextLightState = computeNextLightState(uniquePeaks, mCurrentLightState, config);
        System.arraycopy(nextLightState, 0, mCurrentLightState, 0, nextLightState.length);

        ArrayList<Integer> activeChannels = new ArrayList<>();
        for (int zoneIndex = 0; zoneIndex < nextLightState.length; zoneIndex++) {
            if (nextLightState[zoneIndex] > THRESHOLD) {
                activeChannels.add(zoneIndex);
            }
        }

        int hash = activeChannels.hashCode();
        if (hash == mLastHash) {
            return;
        }
        mLastHash = hash;

        try {
            if (activeChannels.isEmpty()) {
                mGM.turnOff();
                return;
            }

            GlyphFrame.Builder builder = mGM.getGlyphFrameBuilder();
            for (int channel : activeChannels) {
                builder.buildChannel(channel);
            }
            mGM.toggle(builder.build());
        } catch (Exception e) {
            Log.e(TAG, "Glyph update failed: " + e.getMessage(), e);
        }
    }

    private float[] computeNextLightState(
            float[] uniquePeaks,
            float[] currentLightState,
            VisualizerConfig config
    ) {
        float[] nextState = new float[config.zones.length];

        for (int zoneIndex = 0; zoneIndex < config.zones.length; zoneIndex++) {
            float rawZonePeak = 0f;
            int[] overlappingRanges = config.zoneToRangeIndices[zoneIndex];
            for (int rangeIndex : overlappingRanges) {
                if (rangeIndex >= 0 && rangeIndex < uniquePeaks.length) {
                    rawZonePeak = Math.max(rawZonePeak, uniquePeaks[rangeIndex]);
                }
            }

            mZonePeaks[zoneIndex] = Math.max(rawZonePeak, mZonePeaks[zoneIndex] * PEAK_FALLOFF);
            if (mZonePeaks[zoneIndex] < EPSILON) {
                mZonePeaks[zoneIndex] = EPSILON;
            }

            float normalized = clamp01(rawZonePeak / mZonePeaks[zoneIndex]);
            float quadratic = normalized * normalized;
            float mapped = applyPercentSlice(quadratic, config.zones[zoneIndex]);

            float previousLight = currentLightState[zoneIndex];
            float risingEdge = Math.max(previousLight, mapped);
            float decayed = (config.decay * risingEdge) + ((1f - config.decay) * mapped);
            nextState[zoneIndex] = decayed < EPSILON ? 0f : clamp01(decayed);
        }

        return nextState;
    }

    private void ensureStateArrays(int zoneCount) {
        if (mCurrentLightState.length == zoneCount && mZonePeaks.length == zoneCount) {
            return;
        }
        mCurrentLightState = new float[zoneCount];
        mZonePeaks = new float[zoneCount];
        Arrays.fill(mZonePeaks, EPSILON);
        mLastHash = -999;
    }

    private void resetVisualizerState() {
        if (mVisualizerConfig == null) {
            mCurrentLightState = new float[0];
            mZonePeaks = new float[0];
        } else {
            mCurrentLightState = new float[mVisualizerConfig.zones.length];
            mZonePeaks = new float[mVisualizerConfig.zones.length];
            Arrays.fill(mZonePeaks, EPSILON);
        }
        mLastHash = -999;
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
        if (percent >= high) {
            return 1f;
        }
        if (high == low) {
            return 1f;
        }
        return (percent - low) / (high - low);
    }

    private VisualizerConfig loadVisualizerConfig(String presetKey) throws IOException, JSONException {
        String rawJson = loadZonesConfigText();
        JSONObject root = new JSONObject(rawJson);
        JSONObject preset = root.optJSONObject(presetKey);
        if (preset == null) {
            throw new JSONException("Preset '" + presetKey + "' not found in zones.config");
        }

        JSONArray zonesArray = preset.optJSONArray("zones");
        if (zonesArray == null || zonesArray.length() == 0) {
            throw new JSONException("Preset '" + presetKey + "' has no zones");
        }

        double decayAlpha = preset.has("decay-alpha")
                ? preset.optDouble("decay-alpha", 0.8)
                : root.optDouble("decay-alpha", 0.8);
        float adjustedDecay = clamp(0.86f + ((float) decayAlpha / 10f), 0f, 0.9999f);

        ZoneSpec[] zones = new ZoneSpec[zonesArray.length()];
        List<float[]> uniquePairs = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();

        for (int i = 0; i < zonesArray.length(); i++) {
            JSONArray zoneArray = zonesArray.getJSONArray(i);
            float lowHz = (float) zoneArray.getDouble(0);
            float highHz = (float) zoneArray.getDouble(1);
            if (lowHz > highHz) {
                float tmp = lowHz;
                lowHz = highHz;
                highHz = tmp;
            }

            float lowPercent = parseOptionalPercent(zoneArray, 3);
            float highPercent = parseOptionalPercent(zoneArray, 4);
            zones[i] = new ZoneSpec(lowHz, highHz, lowPercent, highPercent);

            String key = String.format(Locale.US, "%.4f|%.4f", lowHz, highHz);
            if (seenPairs.add(key)) {
                uniquePairs.add(new float[]{lowHz, highHz});
            }
        }

        Collections.sort(uniquePairs, new Comparator<float[]>() {
            @Override
            public int compare(float[] left, float[] right) {
                int lowCompare = Float.compare(left[0], right[0]);
                return lowCompare != 0 ? lowCompare : Float.compare(left[1], right[1]);
            }
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
            for (int j = 0; j < overlaps.size(); j++) {
                mapping[j] = overlaps.get(j);
            }
            zoneToRangeIndices[zoneIndex] = mapping;
        }

        return new VisualizerConfig(
                presetKey,
                preset.optString("description", presetKey),
                adjustedDecay,
                zones,
                uniqueRanges,
                zoneToRangeIndices
        );
    }

    private String loadZonesConfigText() throws IOException {
        InputStream in = null;
        try {
            in = getAssets().open("zones.config");
            return readFully(in);
        } catch (IOException ignored) {
            closeQuietly(in);
        }

        ClassLoader loader = AudioCaptureService.class.getClassLoader();
        if (loader != null) {
            in = loader.getResourceAsStream("zones.config");
            if (in != null) {
                try {
                    return readFully(in);
                } finally {
                    closeQuietly(in);
                }
            }
        }

        ApplicationInfo appInfo = getApplicationInfo();
        File externalDir = getExternalFilesDir(null);
        File[] candidates = new File[]{
                new File(getFilesDir(), "zones.config"),
                externalDir == null ? null : new File(externalDir, "zones.config"),
                new File(appInfo.dataDir, "zones.config"),
                new File("zones.config"),
                new File("BetterNothingMusicVisualizer-Android-app/zones.config")
        };

        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile()) {
                return readFile(candidate);
            }
        }

        throw new FileNotFoundException(
                "zones.config not found. Bundle it as an asset or place it in app files."
        );
    }

    private static String readFile(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            return readFully(input);
        } finally {
            closeQuietly(input);
        }
    }

    private static String readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
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
            if (raw instanceof Number) {
                value = ((Number) raw).floatValue();
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
            return clamp(value, 0f, 100f);
        } catch (Exception ignored) {
            return Float.NaN;
        }
    }

    private static float[] buildHannWindow(int size) {
        float[] hann = new float[size];
        for (int i = 0; i < size; i++) {
            hann[i] = 0.5f * (1f - (float) Math.cos((2d * Math.PI * i) / (size - 1)));
        }
        return hann;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static void fft(float[] re, float[] im, int n) {
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
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

        for (int len = 2; len <= n; len <<= 1) {
            double angle = (-2d * Math.PI) / len;
            float wr = (float) Math.cos(angle);
            float wi = (float) Math.sin(angle);

            for (int i = 0; i < n; i += len) {
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

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Glyph Visualizer",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);
        }

        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String content = mVisualizerConfig == null
                ? "zones.config missing"
                : (mDetectedPhoneModel + " - " + mVisualizerConfig.presetKey + " - " + mVisualizerConfig.description);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Glyph Visualizer")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void refreshPresetCatalog() throws IOException, JSONException {
        JSONObject root = loadZonesConfigRoot();
        mDetectedPhoneModel = detectPhoneModel();
        List<String> matching = getPresetKeysForPhoneModel(root, mDetectedPhoneModel);
        if (matching.isEmpty()) {
            matching = getAllPresetKeys(root);
        }
        mAvailablePresetKeys = matching;
    }

    private JSONObject loadZonesConfigRoot() throws IOException, JSONException {
        return new JSONObject(loadZonesConfigText());
    }

    private List<String> getAllPresetKeys(JSONObject root) {
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

    private List<String> getPresetKeysForPhoneModel(JSONObject root, String phoneModel) {
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

    private boolean isPresetEntry(JSONObject root, String key) {
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

    private String chooseDefaultPresetKey(String phoneModel, List<String> presetKeys) {
        if (presetKeys == null || presetKeys.isEmpty()) {
            return DEFAULT_PRESET_KEY;
        }

        String preferred = null;
        if (PHONE_MODEL_PHONE1.equals(phoneModel)) {
            preferred = "np1";
        } else if (PHONE_MODEL_PHONE2.equals(phoneModel)) {
            preferred = "np2";
        } else if (PHONE_MODEL_PHONE2A.equals(phoneModel)) {
            preferred = "np2a";
        } else if (PHONE_MODEL_PHONE3A.equals(phoneModel)) {
            preferred = "np3a";
        } else if (PHONE_MODEL_PHONE3.equals(phoneModel)) {
            preferred = "np3test";
        }

        if (preferred != null && presetKeys.contains(preferred)) {
            return preferred;
        }
        return presetKeys.get(0);
    }

    private String detectPhoneModel() {
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

        String buildText = (
                String.valueOf(Build.MANUFACTURER) + " "
                        + String.valueOf(Build.BRAND) + " "
                        + String.valueOf(Build.MODEL) + " "
                        + String.valueOf(Build.DEVICE) + " "
                        + String.valueOf(Build.PRODUCT)
        ).toLowerCase(Locale.US);

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
}
