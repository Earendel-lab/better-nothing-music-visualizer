/*
////
//////
////////
//////////
////////////
// TO DO LIST HEREEEE:::::
////////////////
//https://taskweb.pages.dev/?board=mauv5VZ29Gw1vnbExSXb#
////////////////
//////////////
////////////
//////////
////////
//////
////
//

///////////////////////////////////////////////////////////
CHANGELOG HERE PLEASE: 2.7 TO 2.8:

2.8 changes:
- Removed legacy hardcoded preset fallback logic and old vocal/bass leftovers from the active runtime path.
- Refactored AudioCaptureService so capture and preset processing are driven by zones.config without duplicated setup code.
- Improved foreground/background service behavior, including cleaner lifecycle handling and quick settings tile refreshes.
- Added live audio route monitoring with AudioDeviceCallback for proper Bluetooth, wired, and speaker hot swapping.
- Fixed auto device memorization so the selected output updates without needing an app restart.
- Fixed latency persistence so each audio route/device can save and restore its own latency value automatically.
- Added a new Haptics tab with a vibration icon and placeholder page.
- Cleaned up old settings/resource leftovers and restored the adaptive launcher background color resource.
- Added fine controls to the latency compensation setting
- Fixed colors
- Added NType font
- More bouncy and snappy animations
- Added more haptics at good places

///////////////////////////////////////////////////////////
*/


package com.better.nothing.music.vizualizer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue


// ─── Tab ─────────────────────────────────────────────────────────────────────
// Promoted to internal so MainViewModel can reference it without reflection.

enum class Tab(val label: String) {
    Audio("Audio"), Glyphs("Glyphs"), Haptics("Vibration"), Settings("Settings"), About("About");

}

private data class AudioRoute(
    val storageKey: String,
    val displayName: String,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────
//
// All mutable state lives here as MutableStateFlow so that:
//   • State survives configuration changes — no full UI rebuild on rotation.
//   • Collectors only recompose the subtree that reads a particular flow.
//   • All IO / CPU work is dispatched off the main thread.

internal class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = application

    // ── Tab ───────────────────────────────────────────────────────────────────
    private val _selectedTab = MutableStateFlow(Tab.Audio)
    val selectedTab = _selectedTab.asStateFlow()
    fun selectTab(tab: Tab) { _selectedTab.value = tab }

    // ── Device ────────────────────────────────────────────────────────────────
    // Exposed as MutableStateFlow (not just a val) so the Activity can always
    // read the latest device synchronously when binding the service.
    val selectedDevice = MutableStateFlow(DeviceProfile.DEVICE_NP2)

    // ── Latency ───────────────────────────────────────────────────────────────
    private val _latencyMs = MutableStateFlow(0)
    val latencyMs = _latencyMs.asStateFlow()

    private val _latencyPresets = MutableStateFlow(listOf(0, 150, 300, 500))
    val latencyPresets = _latencyPresets.asStateFlow()

    /**
     * Updates the current system latency and persists it to disk.
     */
    fun setLatencyMs(value: Int) {
        _latencyMs.value = value
        viewModelScope.launch(Dispatchers.IO) {
            AudioCaptureService.saveLatencyCompensationMs(
                ctx,
                selectedDevice.value,
                activeLatencyRouteKey(),
                value
            )
        }
    }


    // ── Gamma ─────────────────────────────────────────────────────────────────
    private val _gammaValue = MutableStateFlow(AudioCaptureService.DEFAULT_GAMMA)
    val gammaValue = _gammaValue.asStateFlow()
    fun setGammaValue(value: Float) { _gammaValue.value = value }

    // ── Running state ─────────────────────────────────────────────────────────
    private val _runningState = MutableStateFlow(false)
    val runningState = _runningState.asStateFlow()
    fun setRunning(running: Boolean) { _runningState.value = running }

    // ── Presets ──────────────────────────────────────────────────────────────
    private val _selectedPreset = MutableStateFlow("")
    val selectedPreset = _selectedPreset.asStateFlow()
    fun currentPreset(): String = _selectedPreset.value
    fun setSelectedPreset(key: String) { if (key.isNotBlank()) _selectedPreset.value = key }

    private val _presetInfos = MutableStateFlow<List<AudioCaptureService.PresetInfo>>(emptyList())
    val presetInfos = _presetInfos.asStateFlow()

    // ── Device Memorization ──────────────────────────────────────────────────
    private val _autoDeviceEnabled = MutableStateFlow(true)
    val autoDeviceEnabled = _autoDeviceEnabled.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceKey = MutableStateFlow<String?>(null)

    private val _glyphTabEnabled = MutableStateFlow(true)
    val glyphTabEnabled = _glyphTabEnabled.asStateFlow()

    private val _hapticsTabEnabled = MutableStateFlow(true)
    val hapticsTabEnabled = _hapticsTabEnabled.asStateFlow()

    fun setAutoDeviceEnabled(enabled: Boolean): Int {
        _autoDeviceEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            ctx.getSharedPreferences("viz_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("auto_device_enabled", enabled).apply()
        }
        return reloadLatencyForCurrentRoute()
    }

    fun updateConnectedDevice(routeKey: String?, name: String?): Int {
        _connectedDeviceKey.value = routeKey
        _connectedDeviceName.value = name
        return reloadLatencyForCurrentRoute()
    }

    // ── Init: all IO in parallel ──────────────────────────────────────────────

    init {
        // Run EVERYTHING heavy off-thread immediately
        viewModelScope.launch(Dispatchers.Default) {
            val device = DeviceProfile.detectDevice()
            selectedDevice.value = device

            // Load I/O in parallel using IO dispatcher
            launch(Dispatchers.IO) {
                val gamma = AudioCaptureService.loadGamma(ctx)
                val latency = AudioCaptureService.loadLatencyCompensationMs(
                    ctx,
                    device,
                    activeLatencyRouteKey()
                )
                val presets = AudioCaptureService.loadLatencyPresets(ctx)
                val infos = AudioCaptureService.loadPresetInfos(ctx, device)

                // Update UI state once ready
                _gammaValue.value = gamma
                _latencyMs.value = latency
                _latencyPresets.value = presets
                commitPresetInfos(infos)
                val prefs = ctx.getSharedPreferences("viz_prefs", Context.MODE_PRIVATE)
                _autoDeviceEnabled.value = prefs.getBoolean("auto_device_enabled", true)
                _glyphTabEnabled.value = prefs.getBoolean("glyph_tab_enabled", true)
                _hapticsTabEnabled.value = prefs.getBoolean("haptics_tab_enabled", true)
            }

            startRunningStatePoller()
        }
    }

    // ─── Off-thread service state polling ─────────────────────────────────────
    //
    // Previous code ran this on Dispatchers.Main (LaunchedEffect default), waking
    // the UI thread 2× per second for a comparison + potential state write.
    // Moving to Dispatchers.Default keeps the main thread completely idle when
    // nothing has changed.

    private fun startRunningStatePoller() {
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000L) // Polling once per second is enough for a UI label
                val actual = AudioCaptureService.isRunning()
                if (_runningState.value != actual) {
                    _runningState.value = actual
                }
            }
        }
    }

    // ── Public helpers called by the Activity ─────────────────────────────────

    /** Reloads preset list from disk; safe to call from the main thread. */
    fun refreshPresets() {
        viewModelScope.launch(Dispatchers.IO) {
            val infos = AudioCaptureService.loadPresetInfos(ctx, selectedDevice.value)
            withContext(Dispatchers.Main.immediate) { commitPresetInfos(infos) }
        }
    }

    /** Updates preset list in state and persists it; save is off main thread. */
    fun updateLatencyPresets(presets: List<Int>) {
        _latencyPresets.value = presets
        viewModelScope.launch(Dispatchers.IO) {
            AudioCaptureService.saveLatencyPresets(ctx, presets)
        }
    }

    /** Persists gamma to SharedPreferences without blocking the main thread. */
    fun persistGamma(clamped: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            AudioCaptureService.saveGamma(ctx, clamped)
        }
    }

    fun reloadLatencyForCurrentRoute(): Int {
        val latency = AudioCaptureService.loadLatencyCompensationMs(
            ctx,
            selectedDevice.value,
            activeLatencyRouteKey()
        )
        _latencyMs.value = latency
        return latency
    }

    private fun activeLatencyRouteKey(): String? {
        return _connectedDeviceKey.value.takeIf { _autoDeviceEnabled.value }
    }

    private fun commitPresetInfos(infos: List<AudioCaptureService.PresetInfo>) {
        _presetInfos.value = infos
        if (infos.none { it.key == _selectedPreset.value }) {
            _selectedPreset.value = infos.firstOrNull()?.key.orEmpty()
        }
    }
}

// ─── Activity ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    // viewModels() returns the same instance across configuration changes.
    private val viewModel: MainViewModel by viewModels()

    private val audioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var service: AudioCaptureService? = null
    private var bound = false
    private var pendingResultCode = 0
    private var pendingData: Intent? = null
    private var hasPendingToken = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshConnectedAudioRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshConnectedAudioRoute()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d("BetterViz", "Service connected: $name")
            service = (binder as AudioCaptureService.LocalBinder).service
            bound = true
            refreshConnectedAudioRoute()
            applyServiceSettings()
            if (hasPendingToken && pendingData != null) {
                val data = pendingData ?: return
                service?.startCapture(pendingResultCode, data)
                pendingResultCode = 0
                pendingData = null
                hasPendingToken = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            // Use the lightweight static check; the poller will also catch any change.
            viewModel.setRunning(AudioCaptureService.isRunning())
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) @RequiresPermission(
            Manifest.permission.RECORD_AUDIO
        ) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                )
                deliverProjectionToken(result.resultCode, data)
            } else {
                viewModel.setRunning(false)
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchProjection()
            else {
                viewModel.setRunning(false)
                Toast.makeText(
                    this,
                    "Notifications are required while the visualizer is active",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BetterVizTheme {
                // Collect each StateFlow independently. Compose only recomposes the
                // subtree(s) that actually read a value when it changes — collecting
                // them as separate `by` delegates achieves this granularity.
                val tab            by viewModel.selectedTab.collectAsStateWithLifecycle()
                val isRunning      by viewModel.runningState.collectAsStateWithLifecycle()
                val latencyMs      by viewModel.latencyMs.collectAsStateWithLifecycle()
                val latencyPresets by viewModel.latencyPresets.collectAsStateWithLifecycle()
                val gammaValue     by viewModel.gammaValue.collectAsStateWithLifecycle()
                val presets        by viewModel.presetInfos.collectAsStateWithLifecycle()
                val selectedPreset by viewModel.selectedPreset.collectAsStateWithLifecycle()
                val glyphTabEnabled by viewModel.glyphTabEnabled.collectAsStateWithLifecycle()
                val hapticsTabEnabled by viewModel.hapticsTabEnabled.collectAsStateWithLifecycle()

                BetterVizApp(
                    tab = tab,
                    onTabSelected = viewModel::selectTab,
                    isRunning = isRunning,
                    latencyMs = latencyMs,
                    onLatencyChanged = ::onLatencyChanged,
                    latencyPresets = latencyPresets,
                    onLatencyPresetsChanged = viewModel::updateLatencyPresets,
                    gammaValue = gammaValue,
                    onGammaChanged = ::onGammaChanged,
                    presets = presets,
                    selectedPreset = selectedPreset,
                    onPresetSelected = ::onPresetSelected,
                    onToggleVisualizer = ::toggleVisualizer,
                    onAutoDeviceToggle = ::onAutoDeviceToggle,
                    glyphTabEnabled = glyphTabEnabled,
                    hapticsTabEnabled = hapticsTabEnabled,
                    viewModel = viewModel,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        refreshConnectedAudioRoute()
    }

    override fun onStop() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Single source of truth: push the real state into the ViewModel.
        // The poller will keep it in sync while the app is in the foreground.
        viewModel.setRunning(AudioCaptureService.isRunning())
        refreshConnectedAudioRoute()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    // ── Settings delegates ────────────────────────────────────────────────────
    // Each function: (1) clamps, (2) updates ViewModel state (triggers UI),
    // (3) persists to disk off main thread via ViewModel, (4) forwards to service.

    private fun onLatencyChanged(value: Int) {
        viewModel.setLatencyMs(value)
        service?.setLatencyCompensationMs(value)
    }

    private fun onAutoDeviceToggle(enabled: Boolean) {
        val latency = viewModel.setAutoDeviceEnabled(enabled)
        service?.setLatencyCompensationMs(latency)
    }

    private fun onGammaChanged(value: Float) {
        viewModel.setGammaValue(value)
        viewModel.persistGamma(value)            // Dispatchers.IO — never blocks main
        service?.setGamma(value)
    }

    private fun onPresetSelected(key: String) {
        viewModel.setSelectedPreset(key)
        service?.setPreset(key)
    }

    // ── Visualizer lifecycle ──────────────────────────────────────────────────

    private fun toggleVisualizer() {
        if (viewModel.runningState.value) {
            stopEverything()
            viewModel.setRunning(false)
            return
        }
        if (viewModel.currentPreset().isBlank()) {
            viewModel.refreshPresets()
            Toast.makeText(this, "No preset is currently available", Toast.LENGTH_SHORT).show()
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjection()
    }

    private fun launchProjection() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun deliverProjectionToken(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_PRESET_KEY, viewModel.currentPreset())
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        if (!bound) bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        if (bound && service != null) {
            applyServiceSettings()
            service?.startCapture(resultCode, data)
        } else {
            pendingResultCode = resultCode
            pendingData       = data
            hasPendingToken   = true
        }
        viewModel.setRunning(true)
        TileService.requestListeningState(
            this,
            ComponentName(this, VisualizerTileService::class.java)
        )
    }

    /** Reads latest values directly from ViewModel StateFlows — always current. */
    private fun applyServiceSettings() {
        service?.setDevice(viewModel.selectedDevice.value)
        service?.setLatencyCompensationMs(viewModel.latencyMs.value)
        service?.setGamma(viewModel.gammaValue.value)
        val preset = viewModel.currentPreset()
        if (preset.isNotBlank()) service?.setPreset(preset)
    }

    private fun refreshConnectedAudioRoute() {
        val route = resolvePreferredAudioRoute()
        val latency = viewModel.updateConnectedDevice(
            routeKey = route?.storageKey,
            name = route?.displayName ?: "Internal Speaker"
        )
        service?.setLatencyCompensationMs(latency)
    }

    private fun stopEverything() {
        service?.stopCapture()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        service           = null
        pendingResultCode = 0
        pendingData       = null
        hasPendingToken   = false
        stopService(Intent(this, AudioCaptureService::class.java))
        TileService.requestListeningState(
            this,
            ComponentName(this, VisualizerTileService::class.java)
        )
    }

    private fun resolvePreferredAudioRoute(): AudioRoute? {
        val outputs = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(::isUsefulOutputRoute)

        val preferredDevice = outputs.firstOrNull { it.isBluetoothOutput() }
            ?: outputs.firstOrNull { it.isWiredOutput() }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outputs.firstOrNull()

        return preferredDevice?.toAudioRoute()
    }
}

private fun isUsefulOutputRoute(device: AudioDeviceInfo): Boolean {
    return when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> true
        else -> false
    }
}

private fun AudioDeviceInfo.isBluetoothOutput(): Boolean {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            || type == AudioDeviceInfo.TYPE_BLE_HEADSET
            || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            || type == AudioDeviceInfo.TYPE_BLE_BROADCAST
}

private fun AudioDeviceInfo.isWiredOutput(): Boolean {
    return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            || type == AudioDeviceInfo.TYPE_USB_HEADSET
}

private fun AudioDeviceInfo.toAudioRoute(): AudioRoute {
    val routeName = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Internal Speaker"
        else -> productName?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown Output"
    }
    val normalizedName = routeName.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "unknown_output" }
    val normalizedAddress = address
        .lowercase()
        ?.replace(Regex("[^a-z0-9._-]+"), "_")
        ?.trim('_')
        ?.takeIf { it.isNotBlank() }
    val routeKey = listOf(type.toString(), normalizedAddress ?: normalizedName)
        .joinToString("_")

    return AudioRoute(
        storageKey = routeKey,
        displayName = routeName,
    )
}

@Composable
private fun rememberVisibleTabs(
    glyphTabEnabled: Boolean,
    hapticsTabEnabled: Boolean,
): List<Tab> = remember(glyphTabEnabled, hapticsTabEnabled) {
    buildList {
        add(Tab.Audio)
        if (glyphTabEnabled) add(Tab.Glyphs)
        if (hapticsTabEnabled) add(Tab.Haptics)
        add(Tab.Settings)
        add(Tab.About)
    }
}

// ─── Root app composable ──────────────────────────────────────────────────────

// Define a global Bouncy Spring Spec for that "springy" feel
// High-quality Spring Spec: Lower stiffness = more "travel" and bounce
private val BouncySpringSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow
)
@SuppressLint("RestrictedApi")
@Composable
private fun BetterVizApp(
    viewModel: MainViewModel,
    tab: Tab,
    onTabSelected: (Tab) -> Unit,
    isRunning: Boolean,
    latencyMs: Int,
    onLatencyChanged: (Int) -> Unit,
    latencyPresets: List<Int>,
    onLatencyPresetsChanged: (List<Int>) -> Unit,
    gammaValue: Float,
    onGammaChanged: (Float) -> Unit,
    presets: List<AudioCaptureService.PresetInfo>,
    selectedPreset: String,
    onPresetSelected: (String) -> Unit,
    onToggleVisualizer: () -> Unit,
    onAutoDeviceToggle: (Boolean) -> Unit,
    glyphTabEnabled: Boolean,
    hapticsTabEnabled: Boolean,
) {
    val autoDeviceEnabled by viewModel.autoDeviceEnabled.collectAsStateWithLifecycle()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsStateWithLifecycle()
    val rawVisibleTabs = rememberVisibleTabs(glyphTabEnabled, hapticsTabEnabled)

    // Ghost Logic: Add nulls at the start and end to allow overscroll travel
    val visibleTabsWithGhosts = remember(rawVisibleTabs) {
        listOf(null) + rawVisibleTabs + listOf(null)
    }

    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        // Offset by 1 because of the ghost page at index 0
        initialPage = (rawVisibleTabs.indexOf(tab) + 1).coerceAtLeast(1),
        pageCount = { visibleTabsWithGhosts.size }
    )

// 2. Sync Pager -> ViewModel (Logic moved here, removed haptics from here)
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage == 0) {
            pagerState.animateScrollToPage(1, animationSpec = BouncySpringSpec)
        } else if (pagerState.settledPage == visibleTabsWithGhosts.lastIndex) {
            pagerState.animateScrollToPage(visibleTabsWithGhosts.size - 2, animationSpec = BouncySpringSpec)
        } else {
            val targetTab = visibleTabsWithGhosts[pagerState.settledPage]
            if (targetTab != null && targetTab != viewModel.selectedTab.value) {
                onTabSelected(targetTab)
            }
        }
    }

    // Sync ViewModel -> Pager
    LaunchedEffect(tab) {
        val targetPage = visibleTabsWithGhosts.indexOf(tab)
        if (targetPage != -1 && targetPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetPage, animationSpec = BouncySpringSpec)
        }
    }

    // Map current page back to a real Tab for the bottom bar highlight
    val currentTabForHighlight = remember(pagerState.currentPage) {
        visibleTabsWithGhosts.getOrNull(pagerState.currentPage) ?: tab
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .navigationBarsPadding(),
        containerColor = Color.Black,
        floatingActionButton = {
            StartStopButton(running = isRunning, onClick = onToggleVisualizer)
        },
        bottomBar = {
            NativeBottomBar(
                selectedTab = currentTabForHighlight,
                visibleTabs = rawVisibleTabs,
                onTabSelected = { targetTab ->
                    val index = visibleTabsWithGhosts.indexOf(targetTab)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (index != -1 && index != pagerState.currentPage) {
                        scope.launch {
                            pagerState.animateScrollToPage(index, animationSpec = BouncySpringSpec)
                        }
                    }
                }
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = true,
                pageSpacing = 10.dp
            ) { pageIndex ->
                val currentTab = visibleTabsWithGhosts[pageIndex]

                if (currentTab == null) {
                    // This is a ghost page - keep it empty
                    BodyText("Huh\nwhat\nthe\nfuck\nare\nyou\ndoing\nhere\n?")
                    Spacer(modifier = Modifier.fillMaxSize())
                } else {
                    val pageOffset = (
                            (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                            ).absoluteValue

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val fraction = 1f - pageOffset.coerceIn(0f, 1f)
                                // Subtle scaling (0.95 to 1.0)
                                val scale = 0.95f + (1f - 0.95f) * fraction
                                scaleX = scale
                                scaleY = scale
                                alpha = 0.5f + (1f - 0.5f) * fraction
                            }
                    ) {
                        when (currentTab) {
                            Tab.Audio -> AudioScreen(
                                isRunning = isRunning,
                                latencyMs = latencyMs,
                                onLatencyChanged = onLatencyChanged,
                                latencyPresets = latencyPresets,
                                onLatencyPresetsChanged = onLatencyPresetsChanged,
                                autoDeviceEnabled = autoDeviceEnabled,
                                onAutoDeviceToggle = onAutoDeviceToggle,
                                connectedDeviceName = connectedDeviceName,
                            )
                            Tab.Glyphs -> GlyphsScreen(
                                        gammaValue = gammaValue,
                                        onGammaChanged = onGammaChanged,
                                        presets = presets,
                                        selectedPreset = selectedPreset,
                                        onPresetSelected = onPresetSelected,
                                    )
                            Tab.Settings -> SettingsScreen()
                            Tab.Haptics -> HapticsScreen()
                            Tab.About -> AboutScreen()
                        }
                    }
                }
            }
        }
    }
}