package com.better.nothing.music.vizualizer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest


@Composable
fun AudioScreen(
    isRunning: Boolean,
    latencyMs: Int,
    onLatencyChanged: (Int) -> Unit,
    latencyPresets: List<Int>,
    onLatencyPresetsChanged: (List<Int>) -> Unit,
    autoDeviceEnabled: Boolean,
    onAutoDeviceToggle: (Boolean) -> Unit,
    connectedDeviceName: String? = null
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Launcher to handle the Bluetooth permission request
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onAutoDeviceToggle(true)
        }
    }

    // Logic to handle the toggle with permission check
    val handleAutoToggle: (Boolean) -> Unit = { setEnabled ->
        if (setEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val status = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
                if (status == PackageManager.PERMISSION_GRANTED) {
                    onAutoDeviceToggle(true)
                } else {
                    permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            } else {
                onAutoDeviceToggle(true)
            }
        } else {
            onAutoDeviceToggle(false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenTitle(text = "Better Nothing\nMusic Visualizer")

        val descriptionText = if (isRunning) {
            "Real time audio visualizer is active. Your phone is now dancing to the beat! " +
                    "No content is saved, and privacy is respected."
        } else {
            "This app was made by Aleks Levet, rKyzen, and Oliver lebaigue.
            \nTo synchronize the Glyph Interface with your music, this app captures " +
                    "device audio. We use Media Projection for high-fidelity visualization.\n\n" +
                    "Privacy Note: We only utilize the audio stream. No screen content is recorded."
        }
        BodyText(text = descriptionText)

        AnimatedVisibility(visible = isRunning) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                AutoDeviceCard(
                    enabled = autoDeviceEnabled,
                    onToggle = handleAutoToggle,
                    deviceName = connectedDeviceName
                )

                BodyText(
                    text = "Latency compensation ensures Glyphs hit exactly on the beat, " +
                            "especially useful for Bluetooth devices."
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
fun AutoDeviceCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    deviceName: String?
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-Memorize Device",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (enabled)
                        "Saving latency for: ${deviceName ?: "Internal Speaker"}"
                    else "Manual mode (Global latency)",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF000000),
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
@Composable
fun LatencyCard(
    latencyMs: Int,
    onLatencyChanged: (Int) -> Unit,
    latencyPresets: List<Int>,
    onLatencyPresetsChanged: (List<Int>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var draggingIndex by remember { mutableIntStateOf(-1) }

    val visualOrder = remember(latencyPresets) {
        latencyPresets.mapIndexed { i, v -> i to v }
            .sortedBy { it.second }
            .map { it.first }
    }

    LaunchedEffect(visualOrder) {
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    val activeIndex = if (draggingIndex != -1) draggingIndex else latencyPresets.indexOf(latencyMs)

    val updateLatency = { newValue: Int ->
        val clampedValue = newValue.coerceIn(0, 500)
        if (draggingIndex == -1) draggingIndex = latencyPresets.indexOf(latencyMs)

        onLatencyChanged(clampedValue)

        if (draggingIndex != -1) {
            val currentList = latencyPresets.toMutableList()
            val isColliding = currentList.mapIndexed { i, v -> i to v }
                .any { (i, v) -> i != draggingIndex && v == clampedValue }

            if (!isColliding) {
                currentList[draggingIndex] = clampedValue
                onLatencyPresetsChanged(currentList)
            }
        }
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242222)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Latency Compensation",
                color = Color(0xFFE6E1E3),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(), // Necessary to see the alignment effect
                textAlign = TextAlign.Center
            )

            // --- Presets Selector ---
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(0xFF1C1B1B), RoundedCornerShape(24.dp))
                    .padding(4.dp)
            ) {
                val spacing = 4.dp
                val itemWidth = (maxWidth - (spacing * (latencyPresets.size - 1))) / latencyPresets.size

                latencyPresets.forEachIndexed { index, preset ->
                    val isSelected = index == activeIndex
                    val visualIndex = visualOrder.indexOf(index)
                    val targetOffset = (itemWidth + spacing) * visualIndex

                    val animatedX by animateDpAsState(
                        targetValue = targetOffset,
                        animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow),
                        label = "swap"
                    )

                    Box(
                        modifier = Modifier
                            .width(itemWidth)
                            .fillMaxHeight()
                            .offset(x = animatedX)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2B2929))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggingIndex = index
                                onLatencyChanged(preset)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${preset}ms",
                            color = if (isSelected) Color.Black else Color(0xFFE6E1E3),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            ExpressiveSlider(
                value = latencyMs.toFloat(),
                onValueChange = { updateLatency(it.toInt()) },
                valueRange = 0f..500f,
                modifier = Modifier.fillMaxWidth()
            )

            // --- FIXED: Fine-Tuning Row ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(-10, -1, 1, 10).forEach { amount ->
                    // Call it directly. Since we are inside a Row,
                    // the RowScope receiver is automatically available.
                    FineTuneButton(
                        amount = amount,
                        // If your FineTuneButton doesn't accept a modifier yet,
                        // you'll need to update its definition (see below).
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            updateLatency(latencyMs + amount)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RowScope.FineTuneButton(
    amount: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Logic: Force the animation to stay active for at least 100ms
    var isAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> isAnimating = true
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    delay(100) // Minimum "hold" time for the animation to be visible
                    isAnimating = false
                }
            }
        }
    }

    val animatedWeight by animateFloatAsState(
        targetValue = if (isAnimating) 1.3f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.5f, // Bouncier than MediumBouncy
            stiffness = Spring.StiffnessMedium // Medium is more responsive for small buttons
        ),
        label = "weight_bounce"
    )

    val containerColor by animateColorAsState(
        targetValue = if (isAnimating) MaterialTheme.colorScheme.primary else Color(0xFF2B2929),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "color_fade"
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = Modifier
            .weight(animatedWeight)
            .fillMaxHeight()
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (amount > 0) "+$amount" else "$amount",
                style = MaterialTheme.typography.labelMedium,
                color = if (isAnimating)  Color(0xFF000000) else MaterialTheme.colorScheme.primary,
                fontWeight = if (isAnimating) FontWeight.ExtraBold else FontWeight.Medium
            )
        }
    }
}