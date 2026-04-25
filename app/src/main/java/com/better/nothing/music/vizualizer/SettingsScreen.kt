package com.better.nothing.music.vizualizer

import android.content.Context
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

@Composable
fun SettingsScreen(
    glyphTabEnabled: Boolean,
    hapticsTabEnabled: Boolean,
    onGlyphTabToggle: (Boolean) -> Unit,
    onHapticsTabToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("viz_prefs", Context.MODE_PRIVATE)
    }
    val scrollState = rememberScrollState()

    var themeExpanded by remember { mutableStateOf(false) }
    var selectedTheme by remember {
        mutableStateOf(prefs.getString("selected_theme", "Normal") ?: "Normal")
    }

    var fontExpanded by remember { mutableStateOf(false) }
    var selectedFont by remember {
        mutableStateOf(prefs.getString("selected_font", "NDot") ?: "NDot")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .statusBarsPadding()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenTitle(text = stringResource(R.string.settings_title))

        TabVisibilityCard(
            title = stringResource(R.string.glyph_tab),
            description = stringResource(R.string.glyph_tab_description),
            icon = {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Color(0xFFE7E0E7))
            },
            checked = glyphTabEnabled,
            onCheckedChange = onGlyphTabToggle,
        )

        TabVisibilityCard(
            title = stringResource(R.string.haptics_tab),
            description = stringResource(R.string.haptics_tab_description),
            icon = {
                Icon(Icons.Filled.Vibration, contentDescription = null, tint = Color(0xFFE7E0E7))
            },
            checked = hapticsTabEnabled,
            onCheckedChange = onHapticsTabToggle,
        )

        SettingDropdown(
            title = stringResource(R.string.app_theme),
            value = selectedTheme,
            expanded = themeExpanded,
            onExpandedChange = { themeExpanded = !themeExpanded },
            onDismiss = { themeExpanded = false },
            options = listOf(stringResource(R.string.theme_normal), stringResource(R.string.theme_nothing_red)),
            onSelect = { theme ->
                prefs.edit().putString("selected_theme", theme).apply()
                selectedTheme = theme
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
            options = listOf(stringResource(R.string.font_ndot), stringResource(R.string.font_ntype)),
            onSelect = { font ->
                prefs.edit().putString("selected_font", font).apply()
                selectedFont = font
                fontExpanded = false
            },
            helperText = stringResource(R.string.typography_help_text)
        )

        BodyText(text = stringResource(R.string.more_settings_coming))
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun TabVisibilityCard(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF2D332F) else Color(0xFF1D1B1C),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "settings_toggle_card",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF2A2829), RoundedCornerShape(18.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8B8B8),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = {
                    if (checked) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.padding(2.dp),
                        )
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFB5F2B6),
                    checkedTrackColor = Color(0xFF49554A),
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color(0xFFE7E0E7),
                    uncheckedTrackColor = Color(0xFF3D3B3C),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
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
