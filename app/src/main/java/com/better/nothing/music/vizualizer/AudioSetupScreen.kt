package com.better.nothing.music.vizualizer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            .padding(horizontal = 28.dp, vertical = 28.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenTitle(text = "Better Nothing\nMusic Visualizer")
        BodyText(
            text = "To synchronize the Glyph Interface with your music, this app needs to capture " +
                    "process the device's real-time audio output. We use the Media Projection API " +
                    "to ensure a high-fidelity audio capture for the best visualization.\n\n" +
                    "Privacy Note: Don't be scared, we only utilize the audio stream. This app does " +
                    "not record or even view your screen content. Because we bypass video processing " +
                    "entirely, the app remains lightweight and avoids the battery drain associated " +
                    "with traditional screen recording."
        )
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
    var isEditingPresets by remember { mutableStateOf(false) }

    // SnapshotStateList: zero allocations during editing; mutations are tracked
    // by Compose without replacing the whole list reference.
    val editingPresets = remember { SnapshotStateList<String>() }

    Card(
        shape    = RoundedCornerShape(28.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF242222)),
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
                    text  = "Latency adjust :",
                    color = Color(0xFFE6E1E3),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!isEditingPresets) {
                        latencyPresets.forEach { preset ->
                            NativeFilterChip(
                                label    = "${preset}ms",
                                selected = latencyMs == preset,
                                onClick  = { onLatencyChanged(preset) },
                            )
                        }
                        Button(
                            onClick = {
                                editingPresets.clear()
                                editingPresets.addAll(latencyPresets.map { it.toString() })
                                isEditingPresets = true
                            },
                            modifier        = Modifier.height(36.dp),
                            colors          = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF403F44),
                                contentColor   = Color(0xFFE6E0EB)
                            ),
                            contentPadding  = PaddingValues(4.dp),
                        ) {
                            Text("Edit", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        editingPresets.forEachIndexed { index, value ->
                            OutlinedTextField(
                                value         = value,
                                onValueChange = { editingPresets[index] = it },
                                modifier      = Modifier.width(60.dp).height(40.dp),
                                textStyle     = TextStyle(fontSize = 12.sp),
                                singleLine    = true,
                            )
                        }
                        Button(
                            onClick = {
                                try {
                                    val newPresets = editingPresets
                                        .mapNotNull { it.toIntOrNull() }
                                        .filter { it in 0..300 }
                                    if (newPresets.isNotEmpty()) onLatencyPresetsChanged(newPresets)
                                } finally {
                                    isEditingPresets = false
                                }
                            },
                            modifier       = Modifier.height(36.dp),
                            colors         = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB5F2B6),
                                contentColor   = Color(0xFF1C5A21)
                            ),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            Text("Save", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            ExpressiveSlider(
                modifier   = Modifier.fillMaxWidth(),
                value      = latencyMs.toFloat(),
                onValueChange = { onLatencyChanged(it.toInt()) },
                valueRange = 0f..300f,
            )
        }
    }
}
