package com.better.nothing.music.vizualizer

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

@Composable
fun HapticsScreen() {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("viz_prefs", Context.MODE_PRIVATE)
    }
    val scrollState = rememberScrollState()

    var hapticMotorEnabled by remember {
        mutableStateOf(prefs.getBoolean("haptic_motor_enabled", false))
    }
    var hapticFreqMin by remember {
        mutableStateOf(prefs.getInt("haptic_freq_min", 60).toFloat())
    }
    var hapticFreqMax by remember {
        mutableStateOf(prefs.getInt("haptic_freq_max", 250).toFloat())
    }
    var hapticMultiplier by remember {
        mutableStateOf(prefs.getFloat("haptic_multiplier", 1.0f))
    }
    var hapticGamma by remember {
        mutableStateOf(prefs.getFloat("haptic_gamma", 2.0f))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        ScreenTitle(text = stringResource(R.string.haptics_title))
        BodyText(text = stringResource(R.string.haptics_description))

        // Haptic Motor Toggle
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audio-Reactive Haptic Motor",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE6E1E3)
                )
                Switch(
                    checked = hapticMotorEnabled,
                    onCheckedChange = { enabled ->
                        hapticMotorEnabled = enabled
                        prefs.edit().putBoolean("haptic_motor_enabled", enabled).apply()
                    }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Frequency Range (Hz)",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE6E1E3)
                    )

                    // Min Frequency
                    Column {
                        Text(
                            text = "Min: ${hapticFreqMin.toInt()} Hz",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE6E1E3)
                        )
                        ExpressiveSlider(
                            value = hapticFreqMin,
                            onValueChange = { newVal ->
                                hapticFreqMin = newVal.coerceAtMost(hapticFreqMax - 10f)
                                prefs.edit().putInt("haptic_freq_min", hapticFreqMin.toInt()).apply()
                            },
                            valueRange = 20f..2000f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Max Frequency
                    Column {
                        Text(
                            text = "Max: ${hapticFreqMax.toInt()} Hz",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE6E1E3)
                        )
                        ExpressiveSlider(
                            value = hapticFreqMax,
                            onValueChange = { newVal ->
                                hapticFreqMax = newVal.coerceAtLeast(hapticFreqMin + 10f)
                                prefs.edit().putInt("haptic_freq_max", hapticFreqMax.toInt()).apply()
                            },
                            valueRange = 20f..2000f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    BodyText(
                        text = "Haptic motor will vibrate with the amplitude of audio frequencies in this range.",
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Amplitude Multiplier: ${String.format("%.2f", hapticMultiplier)}x",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE6E1E3)
                    )
                    ExpressiveSlider(
                        value = hapticMultiplier,
                        onValueChange = { newVal ->
                            hapticMultiplier = newVal
                            prefs.edit().putFloat("haptic_multiplier", hapticMultiplier).apply()
                        },
                        valueRange = 0.1f..5.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    BodyText(
                        text = "Scales the vibration amplitude (0.1x to 5.0x).",
                        size = 12.sp
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Gamma (Curve): ${String.format("%.2f", hapticGamma)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE6E1E3)
                    )
                    ExpressiveSlider(
                        value = hapticGamma,
                        onValueChange = { newVal ->
                            hapticGamma = newVal
                            prefs.edit().putFloat("haptic_gamma", hapticGamma).apply()
                        },
                        valueRange = 0.5f..4.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    BodyText(
                        text = "Controls the response curve. Higher values emphasize peaks, lower values are more linear.",
                        size = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

