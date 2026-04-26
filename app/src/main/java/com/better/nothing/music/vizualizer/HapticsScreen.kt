package com.better.nothing.music.vizualizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

// Linear position (0..1) to Logarithmic Frequency (20..2000)
fun lerpLog(value: Float, min: Float, max: Float): Float {
    val logMin = ln(min)
    val logMax = ln(max)
    return exp(logMin + (logMax - logMin) * value)
}

// Logarithmic Frequency (20..2000) back to Linear position (0..1)
fun invLerpLog(freq: Float, min: Float, max: Float): Float {
    val logMin = ln(min)
    val logMax = ln(max)
    return (ln(freq) - logMin) / (logMax - logMin)
}

@Composable
fun HapticsScreen(
    hapticMotorEnabled: Boolean,
    onHapticMotorEnabledChanged: (Boolean) -> Unit,
    hapticFreqMin: Float,
    hapticFreqMax: Float,
    onHapticFreqRangeChanged: (Float, Float) -> Unit,
    hapticMultiplier: Float,
    onHapticMultiplierChanged: (Float) -> Unit,
    hapticGamma: Float,
    onHapticGammaChanged: (Float) -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        ScreenTitle(text = "Haptics")
        BodyText(text = "Audio-reactive vibration settings.")

        // Haptic Motor Toggle
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audio-Reactive Haptics",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE6E1E3)
                )
                Switch(
                    checked = hapticMotorEnabled,
                    onCheckedChange = onHapticMotorEnabledChanged
                )
            }
        }

        if (hapticMotorEnabled) {
            // Frequency Range Slider
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Frequency: ${hapticFreqMin.toInt()} - ${hapticFreqMax.toInt()} Hz",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE6E1E3)
                    )

                    val currentRange = invLerpLog(hapticFreqMin, 20f, 1000f)..invLerpLog(hapticFreqMax, 20f, 1000f)

                    ExpressiveRangeSlider(
                        value = currentRange,
                        onValueChange = { newRange ->
                            val newMin = lerpLog(newRange.start, 20f, 1000f)
                            val newMax = lerpLog(newRange.endInclusive, 20f, 1000f)

                            if (newMax - newMin >= 10f) {
                                onHapticFreqRangeChanged(newMin, newMax)
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    BodyText(
                        text = "Vibrates based on audio amplitude in this range.",
                        size = 12.sp
                    )
                }
            }

            // Amplitude Multiplier
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Amplitude: ${"%.2f".format(hapticMultiplier)}x",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE6E1E3)
                    )
                    ExpressiveSlider(
                        value = hapticMultiplier,
                        onValueChange = onHapticMultiplierChanged,
                        valueRange = 0.5f..1.5f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Gamma (Curve)
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Gamma (Response): ${"%.2f".format(hapticGamma)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE6E1E3)
                    )
                    ExpressiveSlider(
                        value = hapticGamma,
                        onValueChange = onHapticGammaChanged,
                        valueRange = 1f..4.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}
