package com.better.nothing.music.vizualizer

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.foundation.layout.size

import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun SettingsScreen(viewModel: MainViewModel) {
    val scrollState = rememberScrollState()

    var themeExpanded by remember { mutableStateOf(false) }
    val selectedTheme by viewModel.selectedTheme.collectAsStateWithLifecycle()

    var fontExpanded by remember { mutableStateOf(false) }
    val selectedFont by viewModel.selectedFont.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        ScreenTitle(text = stringResource(R.string.settings_title))

        SettingDropdown(
            title = stringResource(R.string.app_theme),
            value = selectedTheme,
            expanded = themeExpanded,
            onExpandedChange = { themeExpanded = !themeExpanded },
            onDismiss = { themeExpanded = false },
            options = listOf(
                "OLED Black",
                "Liquorice Black",
                "Nothing Light",
                "Nothing Red",
                "Material You",
                "Material You Light"
            ),
            onSelect = { theme ->
                viewModel.setSelectedTheme(theme)
                themeExpanded = false
            },
            helperText = stringResource(R.string.theme_help_text)
        )

        SettingDropdown(
            title = stringResource(R.string.typography),
            value = selectedFont,
            expanded = fontExpanded,
            onExpandedChange = { fontExpanded = !fontExpanded },
            onDismiss = { fontExpanded = false },
            options = listOf("NDot", "NType"),
            onSelect = { font ->
                viewModel.setSelectedFont(font)
                fontExpanded = false
            },
            helperText = stringResource(R.string.typography_help_text)
        )

        // ── Zones Configuration ──────────────────────────────────────────────
        val configStatus by viewModel.configUpdateStatus.collectAsStateWithLifecycle()
        val context = LocalContext.current

        LaunchedEffect(configStatus) {
            when (val status = configStatus) {
                is MainViewModel.ConfigUpdateStatus.Success -> {
                    Toast.makeText(context, status.message, Toast.LENGTH_SHORT).show()
                    viewModel.resetConfigUpdateStatus()
                }
                is MainViewModel.ConfigUpdateStatus.Error -> {
                    Toast.makeText(context, status.message, Toast.LENGTH_LONG).show()
                    viewModel.resetConfigUpdateStatus()
                }
                else -> {}
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Visualizer Configuration",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Zones Configuration",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "The zones.config file defines how frequencies map to Glyph LEDs. You can update it from GitHub to get the latest presets and device support.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                    )

                    Button(
                        onClick = { viewModel.updateZonesConfig() },
                        enabled = configStatus is MainViewModel.ConfigUpdateStatus.Idle,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (configStatus is MainViewModel.ConfigUpdateStatus.Updating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Updating...")
                        } else {
                            Text("Check for Updates")
                        }
                    }
                }
            }
        }

        BodyText(text = stringResource(R.string.more_settings_coming))
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingDropdown(
    title: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    onDismiss: () -> Unit,
    options: List<String>,
    onSelect: (String) -> Unit,
    helperText: String,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { onExpandedChange() },
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                leadingIcon = {
                    Icon(Icons.Filled.Tune, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismiss,
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onSelect(option) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = helperText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
    }
}
