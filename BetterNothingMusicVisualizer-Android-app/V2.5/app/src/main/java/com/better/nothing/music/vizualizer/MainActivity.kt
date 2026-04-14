package com.better.nothing.music.vizualizer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.res.ColorStateList
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Menu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.animation.togetherWith
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.pow
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import java.util.Locale

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
        DeviceProfile.detectDevice().takeIf { it != DeviceProfile.DEVICE_UNKNOWN } ?: DeviceProfile.DEVICE_NP2
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
            Log.d("BetterViz", "Service bound successfully")
            applyServiceSettings()
            if (hasPendingToken && pendingData != null) {
                Log.d("BetterViz", "Delivering pending projection token")
                val data = pendingData ?: return
                service?.startCapture(pendingResultCode, data)
                pendingResultCode = 0
                pendingData = null
                hasPendingToken = false
                Log.d("BetterViz", "Pending token delivered")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d("BetterViz", "Service disconnected: $name")
            service = null
            bound = false
            runningState = AudioCaptureService.isRunning()
            Log.d("BetterViz", "Service unbound, running=$runningState")
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("BetterViz", "Projection result: code=${result.resultCode}")
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                Log.d("BetterViz", "Projection permission granted, delivering token")
                deliverProjectionToken(result.resultCode, data)
            } else {
                Log.w("BetterViz", "Projection permission denied or data is null")
                runningState = false
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d("BetterViz", "Notification permission: $granted")
            if (granted) {
                Log.d("BetterViz", "Launching projection")
                launchProjection()
            } else {
                runningState = false
                Log.w("BetterViz", "Notification permission denied")
                Toast.makeText(this, "Notifications are required while the visualizer is active", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("BetterViz", "MainActivity.onCreate()")

        gammaValue = AudioCaptureService.loadGamma(this)
        latencyMs = AudioCaptureService.loadLatencyCompensationMs(this, selectedDevice)
        latencyPresets = AudioCaptureService.loadLatencyPresets(this)
        refreshPresets()
        Log.d("BetterViz", "Settings loaded: gamma=$gammaValue, latency=$latencyMs, presets=$latencyPresets")

        setContent {
            BetterVizTheme {
                // Sync running state from service
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(500) // Check every 500ms
                        val currentServiceState = AudioCaptureService.isRunning()
                        if (currentServiceState != runningState) {
                            Log.d("BetterViz", "Syncing UI state: runningState=$runningState -> serviceState=$currentServiceState")
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
        Log.d("BetterViz", "Refreshing presets for device=$selectedDevice")
        presetInfos = AudioCaptureService.loadPresetInfos(this, selectedDevice)
        if (presetInfos.none { it.key == selectedPreset }) {
            selectedPreset = presetInfos.firstOrNull()?.key.orEmpty()
            Log.d("BetterViz", "Preset not found, switched to: $selectedPreset")
        }
        Log.d("BetterViz", "Presets refreshed: ${presetInfos.map { it.key }}")
    }

    private fun selectPreset(presetKey: String) {
        if (presetKey.isBlank()) {
            Log.w("BetterViz", "selectPreset called with empty key")
            return
        }
        Log.d("BetterViz", "Selecting preset: $presetKey")
        selectedPreset = presetKey
        service?.setPreset(presetKey)
    }

    private fun updateLatency(value: Int) {
        latencyMs = AudioCaptureService.clampLatencyCompensationMs(value)
        Log.d("BetterViz", "Latency updated to: $latencyMs ms")
        AudioCaptureService.saveLatencyCompensationMs(this, selectedDevice, latencyMs)
        service?.setLatencyCompensationMs(latencyMs)
    }

    private fun updateLatencyPresets(presets: List<Int>) {
        Log.d("BetterViz", "Latency presets updated: $presets")
        latencyPresets = presets
        AudioCaptureService.saveLatencyPresets(this, presets)
    }

    private fun updateGamma(value: Float) {
        gammaValue = AudioCaptureService.clampGamma(value)
        Log.d("BetterViz", "Gamma updated to: $gammaValue")
        AudioCaptureService.saveGamma(this, gammaValue)
        service?.setGamma(gammaValue)
    }

    private fun toggleVisualizer() {
        Log.d("BetterViz", "toggleVisualizer called, running=$runningState")
        if (runningState) {
            Log.d("BetterViz", "Stopping visualizer")
            stopEverything()
            runningState = false
            return
        }

        if (selectedPreset.isBlank()) {
            Log.d("BetterViz", "No preset selected, refreshing presets")
            refreshPresets()
        }
        Log.d("BetterViz", "Requesting media projection")
        requestProjection()
    }

    private fun requestProjection() {
        Log.d("BetterViz", "Checking notification permission")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.d("BetterViz", "Requesting notification permission")
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjection()
    }

    private fun launchProjection() {
        Log.d("BetterViz", "Launching media projection intent")
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun deliverProjectionToken(resultCode: Int, data: Intent) {
        Log.d("BetterViz", "deliverProjectionToken: resultCode=$resultCode")
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_PRESET_KEY, selectedPreset)
        }

        // Always bind first to ensure service connection is ready
        if (!bound) {
            Log.d("BetterViz", "Binding to service")
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        }
        
        // Start foreground service
        Log.d("BetterViz", "Starting foreground service")
        ContextCompat.startForegroundService(this, serviceIntent)
        
        // Deliver projection token - with retry logic in case binding hasn't completed
        if (bound && service != null) {
            Log.d("BetterViz", "Service bound, applying settings and starting capture")
            applyServiceSettings()
            service?.startCapture(resultCode, data)
        } else {
            Log.d("BetterViz", "Service not yet bound, storing pending token")
            // Service not yet bound, store token for delivery when onServiceConnected fires
            pendingResultCode = resultCode
            pendingData = data
            hasPendingToken = true
        }

        runningState = true
        TileService.requestListeningState(this, ComponentName(this, VisualizerTileService::class.java))
    }

    private fun applyServiceSettings() {
        Log.d("BetterViz", "Applying service settings: device=$selectedDevice, latency=$latencyMs, gamma=$gammaValue, preset=$selectedPreset")
        service?.setDevice(selectedDevice)
        service?.setLatencyCompensationMs(latencyMs)
        service?.setGamma(gammaValue)
        if (selectedPreset.isNotBlank()) {
            service?.setPreset(selectedPreset)
        }
    }

    private fun stopEverything() {
        Log.d("BetterViz", "stopEverything called")
        service?.stopCapture()
        if (bound) {
            Log.d("BetterViz", "Unbinding service")
            unbindService(serviceConnection)
            bound = false
        }
        service = null
        pendingResultCode = 0
        pendingData = null
        hasPendingToken = false
        Log.d("BetterViz", "Stopping service")
        stopService(Intent(this, AudioCaptureService::class.java))
        TileService.requestListeningState(this, ComponentName(this, VisualizerTileService::class.java))
        Log.d("BetterViz", "stopEverything complete")
    }
}

private enum class Tab(val label: String) {
    Audio("Audio"),
    Glyphs("Glyphs"),
    About("About");

    val menuId: Int
        get() = when (this) {
            Audio -> 1
            Glyphs -> 2
            About -> 3
        }
}

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
            text = "So this is where we explain why the app needs the media projection permission. It does not record your screen, it only captures the audio output so the Glyph animation can react in real time."
        )

        AnimatedVisibility(visible = isRunning) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BodyText(
                    text = "This latency compensation slider is for when you're using Bluetooth speakers for example."
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
    val selectedInfo = presets.firstOrNull { it.key == selectedPreset } ?: presets.firstOrNull()

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
                text = "Text explaining what the gamma value does. More means brighter overall with less subtle detail, less is flatter and less punchy.",
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
            text = "Aleks Levet is honestly on another level when it comes to UI design, like the way he captures that clean, futuristic aesthetic inspired by NothingOS is actually insane. Every interface he touches feels intentional, minimal but never empty, detailed but never overwhelming."
        )

        Spacer(modifier = Modifier.height(28.dp))
    }
}

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
        style = TextStyle(
            fontSize = size,
            lineHeight = lineHeight,
            fontWeight = FontWeight.Normal,
        ),
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
    var editingPresets by remember { mutableStateOf(latencyPresets.map { it.toString() }) }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242222)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
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
                                isEditingPresets = true
                                editingPresets = latencyPresets.map { it.toString() }
                            },
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF403F44),
                                contentColor = Color(0xFFE6E0EB)
                            ),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            Text("Edit", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        editingPresets.forEachIndexed { index, value ->
                            androidx.compose.material3.OutlinedTextField(
                                value = value,
                                onValueChange = { 
                                    editingPresets = editingPresets.toMutableList().apply { set(index, it) }
                                },
                                modifier = Modifier.width(60.dp).height(40.dp),
                                textStyle = TextStyle(fontSize = 12.sp),
                                singleLine = true,
                            )
                        }
                        Button(
                            onClick = {
                                try {
                                    val newPresets = editingPresets.map { it.toIntOrNull() ?: 0 }.filter { it >= 0 && it <= 300 }
                                    if (newPresets.isNotEmpty()) {
                                        onLatencyPresetsChanged(newPresets)
                                        isEditingPresets = false
                                    }
                                } catch (e: Exception) {
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

            Slider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                value = latencyMs.toFloat(),
                onValueChange = { onLatencyChanged(it.toInt()) },
                valueRange = 0f..300f,
                colors = expressiveSliderColors(),
            )
        }
    }
}

@Composable
private fun GammaCard(
    gammaValue: Float,
    onGammaChanged: (Float) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242222)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Light Gamma: ${String.format(Locale.US, "%.2f", gammaValue)}",
                color = Color(0xFFE8E0EC),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Slider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                value = gammaValue,
                onValueChange = onGammaChanged,
                valueRange = 0.4f..3.0f,
                colors = expressiveSliderColors(),
            )
        }
    }
}

@Composable
private fun GammaPreviewCard(gammaValue: Float) {
    val animatedGamma by animateFloatAsState(
        targetValue = gammaValue,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "gamma_curve",
    )

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242222)),
        modifier = Modifier.size(130.dp, 122.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(18.dp)) {
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

            val curve = Path().apply {
                moveTo(left, bottom)
                val steps = 64
                for (step in 1..steps) {
                    val x = step / steps.toFloat()
                    val y = x.pow(animatedGamma)
                    lineTo(left + x * width, bottom - y * height)
                }
            }
            drawPath(curve, accent, style = Stroke(width = 5f, cap = StrokeCap.Round))
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
    var isPressed by remember { mutableStateOf(false) }
    
    val containerColor by animateColorAsState(
        targetValue = if (running) Color(0xFFFD9F96) else Color(0xFFB5F2B6),
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 300,
            easing = androidx.compose.animation.core.EaseInOutCubic,
        ),
        label = "fab_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (running) Color(0xFF5A231A) else Color(0xFF1C5A21),
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 300,
            easing = androidx.compose.animation.core.EaseInOutCubic,
        ),
        label = "fab_fg",
    )
    
    val fabScale by animateFloatAsState(
        targetValue = if (isPressed) 1.12f else 1.0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "fab_scale",
    )
    
    val fabHeight by animateFloatAsState(
        targetValue = if (isPressed) 72f else 56f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "fab_height",
    )

    ExtendedFloatingActionButton(
        onClick = {
            onClick()
        },
        modifier = modifier
            .height(fabHeight.dp),
        shape = RoundedCornerShape((fabHeight / 2).dp),
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
            defaultElevation = 8.dp,
            hoveredElevation = 16.dp,
            pressedElevation = 20.dp,
        ),
    ) {
        AnimatedContent(targetState = running, label = "fab_content") { active ->
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Icon(
                    imageVector = if (active) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (active) "Stop" else "Start",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@Composable
@Composable
private fun NativeBottomBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
) {
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        containerColor = Color(0xFF1F1F1F),
        contentColor = Color(0xFFE8E2EA),
        tonalElevation = 16.dp,
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        painter = when (tab) {
                            Tab.Audio -> painterResource(R.drawable.ic_nav_audio)
                            Tab.Glyphs -> painterResource(R.drawable.ic_nav_glyphs)
                            Tab.About -> painterResource(R.drawable.ic_nav_about)
                        },
                        contentDescription = tab.label,
                        modifier = Modifier.size(24.dp),
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFFFFFFFF),
                    selectedTextColor = Color(0xFFFFFFFF),
                    indicatorColor = Color(0xFF3F3E43),
                    unselectedIconColor = Color(0xFF9A959A),
                    unselectedTextColor = Color(0xFF9A959A),
                ),
                modifier = if (isSelected) {
                    Modifier.background(
                        color = Color(0xFF3F3E43),
                        shape = RoundedCornerShape(50),
                    )
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun expressiveSliderColors() = SliderDefaults.colors(
    thumbColor = Color(0xFFF6F2F9),
    activeTrackColor = Color(0xFFE8DBEF),
    inactiveTrackColor = Color(0xFF3F3E43),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
    disabledThumbColor = Color(0xFF8B8694),
    disabledActiveTrackColor = Color(0xFF5A5762),
    disabledInactiveTrackColor = Color(0xFF2A2930),
)

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
