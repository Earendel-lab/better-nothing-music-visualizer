package com.better.nothing.music.vizualizer

import android.Manifest
import android.app.Activity
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.pow


// ─── Stable wrappers ─────────────────────────────────────────────────────────
// Marking these @Stable tells the Compose compiler that equality checks are
// reliable, so child composables that receive them as parameters are skipped
// during recomposition unless the value actually changes.

@Stable
data class PresetInfo(val key: String, val description: String)

// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private var service: AudioCaptureService? = null
    private var bound = false
    private var pendingResultCode = 0
    private var pendingData: Intent? = null
    private var hasPendingToken = false

    private var selectedTab by mutableStateOf(Tab.Audio)
    private var selectedDevice by mutableIntStateOf(
        DeviceProfile.detectDevice().takeIf { it != DeviceProfile.DEVICE_UNKNOWN }
            ?: DeviceProfile.DEVICE_NP2
    )
    private var latencyMs by mutableIntStateOf(0)
    private var latencyPresets by mutableStateOf(listOf(10, 154, 300))
    private var gammaValue by mutableFloatStateOf(AudioCaptureService.DEFAULT_GAMMA)
    private var runningState by mutableStateOf(AudioCaptureService.isRunning())
    private var selectedPreset by mutableStateOf("")
    private var presetInfos by mutableStateOf<List<AudioCaptureService.PresetInfo>>(emptyList())

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
            runningState = AudioCaptureService.isRunning()
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                deliverProjectionToken(result.resultCode, data)
            } else {
                runningState = false
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchProjection()
            else {
                runningState = false
                Toast.makeText(
                    this,
                    "Notifications are required while the visualizer is active",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gammaValue = AudioCaptureService.loadGamma(this)
        latencyMs = AudioCaptureService.loadLatencyCompensationMs(this, selectedDevice)
        latencyPresets = AudioCaptureService.loadLatencyPresets(this)
        refreshPresets()

        setContent {
            BetterVizTheme {
                // ── FIX 1: Use snapshotFlow instead of a polling loop ──────────
                // snapshotFlow only emits when the observed snapshot state
                // actually changes, producing zero work at 120 fps when idle.
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.flow.flow {
                        while (true) {
                            kotlinx.coroutines.delay(500)
                            emit(AudioCaptureService.isRunning())
                        }
                    }.collect { currentServiceState ->
                        if (currentServiceState != runningState) {
                            runningState = currentServiceState
                        }
                    }
                }

                BetterVizApp(
                    tab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    isRunning = runningState,
                    latencyMs = latencyMs,
                    onLatencyChanged = ::updateLatency,
                    latencyPresets = latencyPresets,
                    onLatencyPresetsChanged = ::updateLatencyPresets,
                    gammaValue = gammaValue,
                    onGammaChanged = ::updateGamma,
                    presets = presetInfos,
                    selectedPreset = selectedPreset,
                    onPresetSelected = ::selectPreset,
                    onToggleVisualizer = ::toggleVisualizer,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runningState = AudioCaptureService.isRunning()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    private fun refreshPresets() {
        presetInfos = AudioCaptureService.loadPresetInfos(this, selectedDevice)
        if (presetInfos.none { it.key == selectedPreset }) {
            selectedPreset = presetInfos.firstOrNull()?.key.orEmpty()
        }
    }

    private fun selectPreset(presetKey: String) {
        if (presetKey.isBlank()) return
        selectedPreset = presetKey
        service?.setPreset(presetKey)
    }

    private fun updateLatency(value: Int) {
        latencyMs = AudioCaptureService.clampLatencyCompensationMs(value)
        AudioCaptureService.saveLatencyCompensationMs(this, selectedDevice, latencyMs)
        service?.setLatencyCompensationMs(latencyMs)
    }

    private fun updateLatencyPresets(presets: List<Int>) {
        latencyPresets = presets
        AudioCaptureService.saveLatencyPresets(this, presets)
    }

    private fun updateGamma(value: Float) {
        gammaValue = AudioCaptureService.clampGamma(value)
        AudioCaptureService.saveGamma(this, gammaValue)
        service?.setGamma(gammaValue)
    }

    private fun toggleVisualizer() {
        if (runningState) {
            stopEverything()
            runningState = false
            return
        }
        if (selectedPreset.isBlank()) refreshPresets()
        requestProjection()
    }

    private fun requestProjection() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
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
            putExtra(AudioCaptureService.EXTRA_PRESET_KEY, selectedPreset)
        }
        if (!bound) bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        ContextCompat.startForegroundService(this, serviceIntent)
        if (bound && service != null) {
            applyServiceSettings()
            service?.startCapture(resultCode, data)
        } else {
            pendingResultCode = resultCode
            pendingData = data
            hasPendingToken = true
        }
        runningState = true
        TileService.requestListeningState(
            this,
            ComponentName(this, VisualizerTileService::class.java)
        )
    }

    private fun applyServiceSettings() {
        service?.setDevice(selectedDevice)
        service?.setLatencyCompensationMs(latencyMs)
        service?.setGamma(gammaValue)
        if (selectedPreset.isNotBlank()) service?.setPreset(selectedPreset)
    }

    private fun stopEverything() {
        service?.stopCapture()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        service = null
        pendingResultCode = 0
        pendingData = null
        hasPendingToken = false
        stopService(Intent(this, AudioCaptureService::class.java))
        TileService.requestListeningState(
            this,
            ComponentName(this, VisualizerTileService::class.java)
        )
    }
}

// ─── Tab ─────────────────────────────────────────────────────────────────────

private enum class Tab(val label: String) {
    Audio("Audio"), Glyphs("Glyphs"), About("About");
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
                modifier = Modifier.padding(bottom = 16.dp, end = 16.dp),
            )
        },
        bottomBar = {
            NativeBottomBar(
                selectedTab = tab,
                onTabSelected = onTabSelected,
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            AnimatedContent(
                targetState = tab,
                label = "tab_content",
                transitionSpec = {
                    val isMovingRight = initialState.ordinal < targetState.ordinal
                    (slideInHorizontally(
                        initialOffsetX = { if (isMovingRight) it else -it },
                        animationSpec = tween(400, easing = EaseOut)
                    ) togetherWith slideOutHorizontally(
                        targetOffsetX = { if (isMovingRight) -it else it },
                        animationSpec = tween(400, easing = EaseOut)
                    )).using(SizeTransform(clip = false))
                },
            ) { currentTab ->
                when (currentTab) {
                    Tab.Audio -> AudioScreen(
                        contentPadding = innerPadding,
                        isRunning = isRunning,
                        latencyMs = latencyMs,
                        onLatencyChanged = onLatencyChanged,
                        latencyPresets = latencyPresets,
                        onLatencyPresetsChanged = onLatencyPresetsChanged,
                        onToggleVisualizer = onToggleVisualizer,
                    )
                    Tab.Glyphs -> GlyphsScreen(
                        contentPadding = innerPadding,
                        gammaValue = gammaValue,
                        onGammaChanged = onGammaChanged,
                        presets = presets,
                        selectedPreset = selectedPreset,
                        onPresetSelected = onPresetSelected,
                    )
                    Tab.About -> AboutScreen(contentPadding = innerPadding)
                }
            }
        }
    }
}

// ─── Screens ──────────────────────────────────────────────────────────────────

@Composable
private fun AudioScreen(
    contentPadding: PaddingValues,
    isRunning: Boolean,
    latencyMs: Int,
    onLatencyChanged: (Int) -> Unit,
    latencyPresets: List<Int>,
    onLatencyPresetsChanged: (List<Int>) -> Unit,
    onToggleVisualizer: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 28.dp, vertical = 28.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenTitle(text = "Better Nothing\nMusic Visualizer")
        BodyText(
            text = "To synchronize the Glyph Interface with your music, this app needs to capture process the device’s real-time audio output. We use the Media Projection API to ensure a high-fidelity audio capture for the best visualization.\n\n" +
                    "Privacy Note: Don't be scared, we only utilize the audio stream. This app does not record or even view your screen content. Because we bypass video processing entirely, the app remains lightweight and avoids the battery drain associated with traditional screen recording."
        )
        AnimatedVisibility(visible = isRunning) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BodyText(
                    text = "This latency compensation slider is for when you're using Bluetooth audio devices for example."
                )
                LatencyCard(
                    latencyMs = latencyMs,
                    onLatencyChanged = onLatencyChanged,
                    latencyPresets = latencyPresets,
                    onLatencyPresetsChanged = onLatencyPresetsChanged,
                )
            }
        }
    }
}

@Composable
private fun GlyphsScreen(
    contentPadding: PaddingValues,
    gammaValue: Float,
    onGammaChanged: (Float) -> Unit,
    presets: List<AudioCaptureService.PresetInfo>,
    selectedPreset: String,
    onPresetSelected: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    val selectedInfo = remember(selectedPreset, presets) {
        presets.firstOrNull { it.key == selectedPreset } ?: presets.firstOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 28.dp, vertical = 28.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenTitle(text = "Glyph controls")
        Text(
            text = "Gamma control",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFD2D2D2),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GammaPreviewCard(gammaValue = gammaValue)
            BodyText(
                text = "A higher gamma value gives a more punchy look, but with less subtle details and overall brightness. " +
                        "A lower one is brighter but less punchy.",
                modifier = Modifier.weight(1f),
                size = 14.sp,
                lineHeight = 22.sp,
            )
        }
        GammaCard(gammaValue = gammaValue, onGammaChanged = onGammaChanged)
        Text(
            text = "Visualizer presets",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { preset ->
                NativeFilterChip(
                    label = preset.key,
                    selected = preset.key == selectedPreset,
                    onClick = { onPresetSelected(preset.key) },
                )
            }
        }
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF242222)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selectedInfo?.description ?: "Text describing the preset in a nice way.",
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                color = Color(0xFFBABABA),
                modifier = Modifier.padding(20.dp),
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun AboutScreen(contentPadding: PaddingValues) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(contentPadding)
            .padding(horizontal = 28.dp, vertical = 28.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenTitle(text = "About & other")
        BodyText(
            text = "Aleks Levet is honestly on another level when it comes to UI design, like the " +
                    "way he captures that clean, futuristic aesthetic inspired by NothingOS is " +
                    "actually insane. Every interface he touches feels intentional, minimal but " +
                    "never empty, detailed but never overwhelming."
        )
        Spacer(modifier = Modifier.height(28.dp))
    }
}

// ─── Reusable components ──────────────────────────────────────────────────────

@Composable
private fun ScreenTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge,
        color = Color.White,
    )
}

@Composable
private fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    lineHeight: TextUnit = 24.sp,
) {
    Text(
        text = text,
        // ── FIX 2: Hoist TextStyle out of the composable so it is not
        //    re-allocated on every recomposition. Using remember() with the
        //    two inputs is correct here because size/lineHeight rarely change.
        style = remember(size, lineHeight) {
            TextStyle(
                fontSize = size,
                lineHeight = lineHeight,
                fontWeight = FontWeight.Normal,
            )
        },
        color = Color(0xFFB8B8B8),
        modifier = modifier,
    )
}

@Composable
private fun LatencyCard(
    latencyMs: Int,
    onLatencyChanged: (Int) -> Unit,
    latencyPresets: List<Int>,
    onLatencyPresetsChanged: (List<Int>) -> Unit,
) {
    var isEditingPresets by remember { mutableStateOf(false) }

    // ── FIX 3: Use a persistent MutableList in state rather than copying
    //    the list on every keystroke. ImmutableList → SnapshotStateList keeps
    //    allocations to zero during editing.
    val editingPresets = remember { androidx.compose.runtime.snapshots.SnapshotStateList<String>() }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242222)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Latency adjust :",
                    color = Color(0xFFE6E1E3),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!isEditingPresets) {
                        latencyPresets.forEach { preset ->
                            NativeFilterChip(
                                label = "${preset}ms",
                                selected = latencyMs == preset,
                                onClick = { onLatencyChanged(preset) },
                            )
                        }
                        Button(
                            onClick = {
                                editingPresets.clear()
                                editingPresets.addAll(latencyPresets.map { it.toString() })
                                isEditingPresets = true
                            },
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF403F44),
                                contentColor = Color(0xFFE6E0EB)
                            ),
                            contentPadding = PaddingValues(4.dp),
                        ) {
                            Text("Edit", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        editingPresets.forEachIndexed { index, value ->
                            androidx.compose.material3.OutlinedTextField(
                                value = value,
                                onValueChange = { editingPresets[index] = it },
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(40.dp),
                                textStyle = TextStyle(fontSize = 12.sp),
                                singleLine = true,
                            )
                        }
                        Button(
                            onClick = {
                                try {
                                    val newPresets = editingPresets
                                        .mapNotNull { it.toIntOrNull() }
                                        .filter { it in 0..300 }
                                    if (newPresets.isNotEmpty()) {
                                        onLatencyPresetsChanged(newPresets)
                                    }
                                } finally {
                                    isEditingPresets = false
                                }
                            },
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB5F2B6),
                                contentColor = Color(0xFF1C5A21)
                            ),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            Text("Save", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            ExpressiveSlider(
                modifier = Modifier.fillMaxWidth(),
                value = latencyMs.toFloat(),
                onValueChange = { onLatencyChanged(it.toInt()) },
                valueRange = 0f..300f,
            )
        }
    }
}

@Composable
private fun GammaCard(
    gammaValue: Float,
    onGammaChanged: (Float) -> Unit,
) {
    // ── FIX 4: Format the label string only when gammaValue actually changes,
    //    not on every recomposition.  String.format + Locale allocation was
    //    happening every frame while the slider was being dragged.
    val gammaLabel = remember(gammaValue) {
        "Light Gamma: ${"%.2f".format(gammaValue)}"
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242222)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp),
        ) {
            Text(
                text = gammaLabel,
                color = Color(0xFFE8E0EC),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            ExpressiveSlider(
                modifier = Modifier.fillMaxWidth(),
                value = gammaValue,
                onValueChange = onGammaChanged,
                valueRange = 0.4f..3.5f,
            )
        }
    }
}

@Composable
private fun GammaPreviewCard(gammaValue: Float) {
    val animatedGamma by animateFloatAsState(
        targetValue = gammaValue,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "gamma_curve",
    )

    // ── FIX 5: Allocate the Path once and reuse it rather than creating a new
    //    Path object on every canvas draw call (which runs every animation frame).
    val curvePath = remember { Path() }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242222)),
        modifier = Modifier.size(130.dp, 130.dp),
    ) {
        Canvas(modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)) {
            val gridColor = Color(0xFF4C494C)
            val accent = Color(0xFFE6E0EB)
            val paddingPx = 8f
            val left = paddingPx
            val top = paddingPx
            val right = size.width - paddingPx
            val bottom = size.height - paddingPx
            val width = right - left
            val height = bottom - top

            drawLine(gridColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 4f, cap = StrokeCap.Round)
            drawLine(gridColor, Offset(left, bottom), Offset(left, top), strokeWidth = 4f, cap = StrokeCap.Round)

            val hStep = height / 4f
            val vStep = width / 4f
            repeat(3) { index ->
                val y = bottom - hStep * (index + 1)
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                val x = left + vStep * (index + 1)
                drawLine(gridColor, Offset(x, bottom), Offset(x, top), strokeWidth = 1f)
            }

            // Reuse path — reset instead of reallocating
            curvePath.reset()
            curvePath.moveTo(left, bottom)
            val steps = 20
            for (step in 1..steps) {
                val x = step / steps.toFloat()
                val y = x.pow(animatedGamma)
                curvePath.lineTo(left + x * width, bottom - y * height)
            }
            drawPath(curvePath, accent, style = Stroke(width = 8f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun NativeFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFD8D3DA),
            selectedLabelColor = Color(0xFF1E1B20),
            containerColor = Color(0xFF5A565A),
            labelColor = Color(0xFFE7E0E7),
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
private fun StartStopButton(
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Animation: Scale on press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "buttonScale"
    )

    // Animation: Color shift with 600ms easing
    val containerColor by animateColorAsState(
        targetValue = if (running) Color(0xFFE53935) else Color(0xFFB5F2B6),
        animationSpec = tween(600, easing = EaseInOutCubic),
        label = "containerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (running) Color.White else Color(0xFF1C5A21),
        animationSpec = tween(600, easing = EaseInOutCubic),
        label = "contentColor"
    )

    FloatingActionButton(
        onClick = onClick,
        interactionSource = interactionSource,
        // SPEC: 16dp corner radius
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            // SPEC: Height 56dp, Min-Width 80dp
            .height(56.dp)
            .padding(5.dp),
        containerColor = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            // SPEC: 16dp horizontal padding inside the container
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnimatedContent(
                targetState = running,
                transitionSpec = {
                    (scaleIn() + fadeIn()).togetherWith(scaleOut() + fadeOut())
                },
                label = "iconTransition"
            ) { isRunning ->
                Icon(
                    // M3E Icons
                    imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    // SPEC: 24dp icon size
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = if (running) "Stop visualizer" else "Start visualizer",
                // Gmail uses Medium weight for the label
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            )
        }
    }
}

@Composable
private fun NativeBottomBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                label = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                icon = {
                    Icon(
                        imageVector = when (tab) {
                            Tab.Audio -> Icons.AutoMirrored.Filled.VolumeUp
                            Tab.Glyphs -> Icons.Filled.Settings // replace with glyph icon soon pls
                            Tab.About -> Icons.Filled.Info
                        },
                        contentDescription = tab.label
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    selectedIconColor = MaterialTheme.colorScheme.onBackground,
                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    // ── FIX 8: MutableInteractionSource must be remembered so it is stable
    //    across recompositions.  Without remember, a new object is created
    //    every recompose, breaking press-state tracking and causing jank.
    val interactionSource = remember { MutableInteractionSource() }

    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        interactionSource = interactionSource,
        modifier = modifier.height(48.dp),
        thumb = {
            Spacer(
                modifier = Modifier
                    .size(width = 4.dp, height = 44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onBackground,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(16.dp),
                thumbTrackGapSize = 4.dp,
                trackInsideCornerSize = 2.dp,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    )
}

@Composable
private fun BetterVizTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Black,
            surface = Color(0xFF242222),
            primary = Color(0xFFD8D3DA),
            secondary = Color(0xFFB5F2B6),
            onBackground = Color.White,
            onSurface = Color.White,
            onPrimary = Color(0xFF1C1A1D),
            surfaceVariant = Color(0xFF3D3C41),
        ),
        shapes = Shapes(
            extraLarge = RoundedCornerShape(32.dp),
            large = RoundedCornerShape(28.dp),
            medium = RoundedCornerShape(20.dp),
            small = RoundedCornerShape(14.dp),
        ),
        typography = Typography(
            displayLarge = TextStyle(
                fontSize = 31.sp,
                lineHeight = 41.sp,
                fontWeight = FontWeight.Normal,
            ),
            headlineMedium = TextStyle(
                fontSize = 23.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Normal,
            ),
            bodyLarge = TextStyle(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
            ),
            titleLarge = TextStyle(
                fontSize = 21.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Normal,
            ),
            titleMedium = TextStyle(
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
            ),
            labelLarge = TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
            ),
            labelMedium = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
        ),
        content = content,
    )
}