package com.better.nothing.music.vizualizer

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


// ─── Tab ─────────────────────────────────────────────────────────────────────
// Promoted to internal so MainViewModel can reference it without reflection.

enum class Tab(val label: String) {
    Audio("Audio"), Glyphs("Glyphs"), About("About");

    companion object {
        // Allocated once at class-load time; never re-allocated during recomposition.
        val all: List<Tab> = entries
    }
}

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
            AudioCaptureService.saveLatencyCompensationMs(ctx, selectedDevice.value, value)
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

    // ── Presets ───────────────────────────────────────────────────────────────
    private val _selectedPreset = MutableStateFlow("")
    val selectedPreset = _selectedPreset.asStateFlow()
    fun currentPreset(): String = _selectedPreset.value
    fun setSelectedPreset(key: String) { if (key.isNotBlank()) _selectedPreset.value = key }

    private val _presetInfos = MutableStateFlow<List<AudioCaptureService.PresetInfo>>(emptyList())
    val presetInfos = _presetInfos.asStateFlow()

    // ── Init: all IO in parallel ──────────────────────────────────────────────

    init {
        viewModelScope.launch {
            // Device detection is CPU work — run on Default.
            val device = withContext(Dispatchers.Default) {
                DeviceProfile.detectDevice()
                    .takeIf { it != DeviceProfile.DEVICE_UNKNOWN }
                    ?: DeviceProfile.DEVICE_NP2
            }
            selectedDevice.value = device

            // Load data in parallel
            withContext(Dispatchers.IO) {
                val gammaD   = async { AudioCaptureService.loadGamma(ctx) }
                val latencyD = async { AudioCaptureService.loadLatencyCompensationMs(ctx, device) }
                val presetsD = async { AudioCaptureService.loadLatencyPresets(ctx) }
                val infosD   = async { AudioCaptureService.loadPresetInfos(ctx, device) }

                // Assign values to flows on the Main thread after awaiting
                val g = gammaD.await()
                val l = latencyD.await()
                val p = presetsD.await()
                val i = infosD.await()

                withContext(Dispatchers.Main) {
                    _gammaValue.value = g
                    _latencyMs.value = l
                    _latencyPresets.value = p
                    commitPresetInfos(i)
                }
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
                delay(500L)
                val running = AudioCaptureService.isRunning()
                // Only emit when the value actually changes — prevents spurious
                // recompositions from a StateFlow that emits the same value.
                if (running != _runningState.value) _runningState.value = running
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

    /** Persists latency compensation to SharedPreferences without blocking the main thread. */
    fun persistLatency(clamped: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            AudioCaptureService.saveLatencyCompensationMs(ctx, selectedDevice.value, clamped)
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

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var service: AudioCaptureService? = null
    private var bound = false
    private var pendingResultCode = 0
    private var pendingData: Intent? = null
    private var hasPendingToken = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d("BetterViz", "Service connected: $name")
            service = (binder as AudioCaptureService.LocalBinder).service
            bound = true
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
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
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

                BetterVizApp(
                    tab                     = tab,
                    onTabSelected           = viewModel::selectTab,
                    isRunning               = isRunning,
                    latencyMs               = latencyMs,
                    onLatencyChanged        = ::onLatencyChanged,
                    latencyPresets          = latencyPresets,
                    onLatencyPresetsChanged = viewModel::updateLatencyPresets,
                    gammaValue              = gammaValue,
                    onGammaChanged          = ::onGammaChanged,
                    presets                 = presets,
                    selectedPreset          = selectedPreset,
                    onPresetSelected        = ::onPresetSelected,
                    onToggleVisualizer      = ::toggleVisualizer,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Single source of truth: push the real state into the ViewModel.
        // The poller will keep it in sync while the app is in the foreground.
        viewModel.setRunning(AudioCaptureService.isRunning())
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
        viewModel.persistLatency(value)          // Dispatchers.IO — never blocks main
        service?.setLatencyCompensationMs(value)
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
        // refreshPresets is now async/IO — safe to call from main thread.
        if (viewModel.currentPreset().isBlank()) viewModel.refreshPresets()
        requestProjection()
    }

    private fun requestProjection() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjection()
    }

    private fun launchProjection() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun deliverProjectionToken(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_PRESET_KEY, viewModel.currentPreset())
        }
        if (!bound) bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        ContextCompat.startForegroundService(this, serviceIntent)
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
}

// ─── Root app composable ──────────────────────────────────────────────────────

@Composable
private fun BetterVizApp(
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
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        containerColor = Color.Black,
        floatingActionButton = {
            StartStopButton(
                running = isRunning,
                onClick = onToggleVisualizer,
                modifier = Modifier.padding(bottom = 8.dp, end = 8.dp),
            )
        },
        bottomBar = {
            NativeBottomBar(
                selectedTab = tab,
                onTabSelected = onTabSelected,
            )
        },
    ) { innerPadding ->
        // We use a Box here to "consume" the Scaffold padding once, 
        // preventing the AnimatedContent from jittering.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            AnimatedContent(
                targetState = tab,
                label = "tab_content",
                transitionSpec = {
                    val isMovingRight = targetState.ordinal > initialState.ordinal

                    // Spring physics: No bounce, but very smooth deceleration
                    val springSpec = spring<IntOffset>(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )

                    // Subtle scale and fade specs
                    val fadeSpec = tween<Float>(durationMillis = 300)
                    val scaleSpec = tween<Float>(durationMillis = 400, easing = EaseOutQuart)

                    if (isMovingRight) {
                        (slideInHorizontally(initialOffsetX = { it }, animationSpec = springSpec) +
                                fadeIn(fadeSpec) +
                                scaleIn(initialScale = 0.95f, animationSpec = scaleSpec))
                            .togetherWith(
                                slideOutHorizontally(targetOffsetX = { -it }, animationSpec = springSpec) +
                                        fadeOut(fadeSpec) +
                                        scaleOut(targetScale = 0.95f, animationSpec = scaleSpec)
                            )
                    } else {
                        (slideInHorizontally(initialOffsetX = { -it }, animationSpec = springSpec) +
                                fadeIn(fadeSpec) +
                                scaleIn(initialScale = 0.95f, animationSpec = scaleSpec))
                            .togetherWith(
                                slideOutHorizontally(targetOffsetX = { it }, animationSpec = springSpec) +
                                        fadeOut(fadeSpec) +
                                        scaleOut(targetScale = 0.95f, animationSpec = scaleSpec)
                            )
                    }.using(SizeTransform(clip = false))
                },
                modifier = Modifier.fillMaxSize()
            ) { currentTab ->
                // Inside here, we no longer pass innerPadding down, 
                // as the parent Box is already handling it.
                when (currentTab) {
                    Tab.Audio -> AudioScreen(
                        isRunning = isRunning,
                        latencyMs = latencyMs,
                        onLatencyChanged = onLatencyChanged,
                        latencyPresets = latencyPresets,
                        onLatencyPresetsChanged = onLatencyPresetsChanged,
                    )

                    Tab.Glyphs -> GlyphsScreen(
                        gammaValue = gammaValue,
                        onGammaChanged = onGammaChanged,
                        presets = presets,
                        selectedPreset = selectedPreset,
                        onPresetSelected = onPresetSelected,
                    )

                    Tab.About -> AboutScreen()
                }
            }
        }
    }
}
