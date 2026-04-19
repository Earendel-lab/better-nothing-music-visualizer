package com.better.nothing.music.vizualizer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun AudioScreen(
    isRunning: Boolean,
    latencyMs: Int,
    onLatencyChanged: (Int) -> Unit,
    latencyPresets: List<Int>,
    onLatencyPresetsChanged: (List<Int>) -> Unit,
) {
    val scrollState = rememberScrollState()
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
            "Real time audio visualizer is active. Your phone is now dancing to the beat! Keep in mind, no content is saved on device, and the screen is NOT recorded."
        } else {
            "To synchronize the Glyph Interface with your music, this app needs to capture and " +
            "process the device's real-time audio output. We use the Media Projection API " +
            "to ensure a high-fidelity audio capture for the best visualization.\n\n" +
            "Privacy Note: You will see a popup similar to the screen recording one. Don't be scared, we only utilize the audio stream. This app does " +
            "not record or even view your screen content. Because we bypass video processing " +
            "entirely, the app remains lightweight and avoids the battery drain associated " +
            "with traditional screen recording."
        }
        BodyText(text = descriptionText)

        AnimatedVisibility(visible = isRunning) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BodyText(
                    text = "This latency compensation slider is for when you're using Bluetooth " +
                            "audio devices for example."
                )
                LatencyCard(
                    latencyMs               = latencyMs,
                    onLatencyChanged        = onLatencyChanged,
                    latencyPresets          = latencyPresets,
                    onLatencyPresetsChanged = onLatencyPresetsChanged,
                )
            }
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
            )

            // Container switched to LazyRow for reordering support
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Color(0xFF1C1B1B), RoundedCornerShape(24.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                userScrollEnabled = false // Keep it static like a toggle bar
            ) {
                itemsIndexed(
                    items = latencyPresets,
                    // KEY is critical: it lets Compose track the item even when sorted
                    key = { _, preset -> preset }
                ) { index, preset ->
                    val isSelected = preset == latencyMs

                    val cornerSize = when (index) {
                        0 -> RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp)
                        latencyPresets.size - 1 -> RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp, topStart = 4.dp, bottomStart = 4.dp)
                        else -> RoundedCornerShape(4.dp)
                    }

                    Box(
                        modifier = Modifier
                            // 1. FILL the available space (LazyRow equivalent of weight)
                            .fillParentMaxWidth(1f / latencyPresets.size)
                            .fillMaxHeight()
                            // 2. THE ANIMATION: This handles the bouncy swap
                            .animateItem(
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                // REMOVE THESE to stop the unwanted fade effect
                                fadeInSpec = null,
                                fadeOutSpec = null
                            )
                            .clip(cornerSize)
                            .background(if (isSelected) Color(0xFF403F44) else Color(0xFF2B2929))
                            .clickable { onLatencyChanged(preset) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${preset}ms",
                            color = if (isSelected) Color(0xFFB5F2B6) else Color(0xFFE6E1E3),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            ExpressiveSlider(
                value = latencyMs.toFloat(),
                onValueChange = { newValue ->
                    val intValue = newValue.toInt()
                    onLatencyChanged(intValue)

                    if (intValue != latencyMs && !latencyPresets.contains(intValue)) {
                        val currentList = latencyPresets.toMutableList()
                        val activeIndex = latencyPresets.indexOf(latencyMs)

                        if (activeIndex != -1) {
                            currentList[activeIndex] = intValue
                            // The sort here triggers the LazyRow reorder animation
                            onLatencyPresetsChanged(currentList.sorted())
                        }
                    }
                },
                valueRange = 0f..500f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}