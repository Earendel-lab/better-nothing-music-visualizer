package com.better.nothing.music.vizualizer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.res.ColorStateList
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.TileService
import android.view.Menu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView

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
    private var gammaValue by mutableFloatStateOf(AudioCaptureService.DEFAULT_GAMMA)
    private var runningState by mutableStateOf(AudioCaptureService.isRunning())
    private var selectedPreset by mutableStateOf("")
    private var presetInfos by mutableStateOf<List<AudioCaptureService.PresetInfo>>(emptyList())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
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
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                deliverProjectionToken(result.resultCode, result.data!!)
            } else {
                runningState = false
                Toast.makeText(this, "Audio capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchProjection()
            } else {
                runningState = false
                Toast.makeText(this, "Notifications are required while the visualizer is active", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gammaValue = AudioCaptureService.loadGamma(this)
        latencyMs = AudioCaptureService.loadLatencyCompensationMs(this, selectedDevice)
        refreshPresets()

        setContent {
            BetterVizTheme {
                BetterVizApp(
                    tab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    isRunning = runningState,
                    latencyMs = latencyMs,
                    onLatencyChanged = ::updateLatency,
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

        if (selectedPreset.isBlank()) {
            refreshPresets()
        }
        requestProjection()
    }

    private fun requestProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
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

        ContextCompat.startForegroundService(this, serviceIntent)
        if (bound && service != null) {
            applyServiceSettings()
            service?.startCapture(resultCode, data)
        } else {
            pendingResultCode = resultCode
            pendingData = data
            hasPendingToken = true
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        }

        runningState = true
        TileService.requestListeningState(this, ComponentName(this, VisualizerTileService::class.java))
    }

    private fun applyServiceSettings() {
        service?.setDevice(selectedDevice)
        service?.setLatencyCompensationMs(latencyMs)
        service?.setGamma(gammaValue)
        if (selectedPreset.isNotBlank()) {
            service?.setPreset(selectedPreset)
        }
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
        TileService.requestListeningState(this, ComponentName(this, VisualizerTileService::class.java))
    }
}

private enum class Tab {
    Audio,
    Glyphs,
    About;

    val menuId: Int
        get() = when (this) {
            Audio -> 1
            Glyphs -> 2
            About -> 3
        }
}

private val latencyPresets = listOf(10, 154, 300)

@Composable
private fun BetterVizApp(
    tab: Tab,
    onTabSelected: (Tab) -> Unit,
    isRunning: Boolean,
    latencyMs: Int,
    onLatencyChanged: (Int) -> Unit,
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
            ) { currentTab ->
                when (currentTab) {
                    Tab.Audio -> AudioScreen(
                        contentPadding = innerPadding,
                        isRunning = isRunning,
                        latencyMs = latencyMs,
                        onLatencyChanged = onLatencyChanged,
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
                    text = "Lil text explaining the latency thing so the pulses line up better when your phone feels just a tiny bit behind."
                )

                LatencyCard(
                    latencyMs = latencyMs,
                    onLatencyChanged = onLatencyChanged,
                )
            }
        }

        Spacer(modifier = Modifier.height(if (isRunning) 12.dp else 140.dp))

        StartStopButton(
            running = isRunning,
            onClick = onToggleVisualizer,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(modifier = Modifier.height(28.dp))
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
            text = "ItzNotHj is honestly on another level when it comes to UI design, like the way he captures that clean, futuristic aesthetic inspired by NothingOS is actually insane. Every interface he touches feels intentional, minimal but never empty, detailed but never overwhelming."
        )

        BodyText(
            text = "He understands spacing, typography, and transparency in a way that makes everything feel smooth and premium, almost like you are interacting with something ahead of its time. The way he uses monochrome palettes mixed with subtle accents gives his designs that signature Nothing feel while still keeping his own identity strong."
        )

        BodyText(
            text = "It is not just about looking good either, his layouts feel usable, intuitive, and thought out. Straight up, ItzNotHj is not just designing interfaces, he is crafting experiences that feel modern, clean, and actually inspiring. Written by Aleks Levet."
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
) {
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
                    latencyPresets.forEach { preset ->
                        NativeFilterChip(
                            label = "${preset}ms",
                            selected = latencyMs == preset,
                            onClick = { onLatencyChanged(preset) },
                        )
                    }
                }
            }

            Slider(
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
                text = "Light Gamma: ${String.format("%.2f", gammaValue)}",
                color = Color(0xFFE8E0EC),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Slider(
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
            val left = 0f
            val bottom = size.height
            val right = size.width
            val top = 0f

            drawLine(gridColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 4f, cap = StrokeCap.Round)
            drawLine(gridColor, Offset(left, bottom), Offset(left, top), strokeWidth = 4f, cap = StrokeCap.Round)

            val hStep = size.height / 4f
            val vStep = size.width / 4f
            repeat(3) { index ->
                val y = bottom - hStep * (index + 1)
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                val x = vStep * (index + 1)
                drawLine(gridColor, Offset(x, bottom), Offset(x, top), strokeWidth = 1f)
            }

            val curve = Path().apply {
                moveTo(left + 2f, bottom - 2f)
                cubicTo(
                    size.width * 0.18f,
                    size.height * (0.75f - animatedGamma * 0.08f),
                    size.width * 0.52f,
                    size.height * 0.1f,
                    right - 2f,
                    top + 2f + (3f - animatedGamma) * 4f,
                )
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
    val containerColor by animateColorAsState(
        targetValue = if (running) Color(0xFFFD9F96) else Color(0xFFB5F2B6),
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "toggle_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (running) Color(0xFF5A231A) else Color(0xFF1C5A21),
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "toggle_fg",
    )
    val icon = if (running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow
    val label = if (running) "Stop" else "Start"

    Button(
        onClick = onClick,
        modifier = modifier
            .width(176.dp)
            .height(56.dp),
        shape = RoundedCornerShape(30.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        AnimatedContent(targetState = running, label = "toggle_content") { active ->
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (active) "Stop" else "Start",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                    imageVector = if (active) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun NativeBottomBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        factory = { context ->
            val selectedColor = 0xFFE8E2EA.toInt()
            val unselectedColor = 0xFFBEB8C0.toInt()
            val indicatorColor = 0xFF4C4A4D.toInt()

            BottomNavigationView(context).apply {
                setBackgroundColor(0xFF1F1F1F.toInt())
                itemIconTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(),
                    ),
                    intArrayOf(selectedColor, unselectedColor),
                )
                itemTextColor = itemIconTintList
                itemActiveIndicatorColor = ColorStateList.valueOf(indicatorColor)
                labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED

                menu.add(Menu.NONE, Tab.Audio.menuId, Menu.NONE, "Audio").setIcon(R.drawable.ic_nav_audio)
                menu.add(Menu.NONE, Tab.Glyphs.menuId, Menu.NONE, "Glyphs").setIcon(R.drawable.ic_nav_glyphs)
                menu.add(Menu.NONE, Tab.About.menuId, Menu.NONE, "About").setIcon(R.drawable.ic_nav_about)

                setOnItemSelectedListener { item ->
                    when (item.itemId) {
                        Tab.Audio.menuId -> onTabSelected(Tab.Audio)
                        Tab.Glyphs.menuId -> onTabSelected(Tab.Glyphs)
                        Tab.About.menuId -> onTabSelected(Tab.About)
                    }
                    true
                }
            }
        },
        update = { navView ->
            if (navView.selectedItemId != selectedTab.menuId) {
                navView.selectedItemId = selectedTab.menuId
            }
        },
    )
}

@Composable
private fun expressiveSliderColors() = SliderDefaults.colors(
    thumbColor = Color(0xFFE6E0EB),
    activeTrackColor = Color(0xFFD8D3DA),
    inactiveTrackColor = Color(0xFF545154),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
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
