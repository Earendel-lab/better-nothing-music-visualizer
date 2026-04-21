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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("viz_prefs", Context.MODE_PRIVATE)
    var expanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var selectedTheme by remember {
        mutableStateOf(prefs.getString("selected_theme", "Normal") ?: "Normal")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .statusBarsPadding()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenTitle(text = "Settings")

        // Theme Selector Section
        Column {
            Text(
                text = "App Theme",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            @OptIn(ExperimentalMaterial3Api::class)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedTheme,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Normal") },
                        onClick = {
                            coroutineScope.launch {
                                prefs.edit().putString("selected_theme", "Normal").apply()
                                selectedTheme = "Normal"
                            }
                            expanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Nothing Red") },
                        onClick = {
                            coroutineScope.launch {
                                prefs.edit().putString("selected_theme", "Nothing Red").apply()
                                selectedTheme = "Nothing Red"
                            }
                            expanded = false
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Changes apply immediately and persist across app restarts.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.Gray
            )
        }

            // Font Toggle Section
            Column(
                modifier = Modifier.padding(top = 32.dp)
            ) {
                Text(
                    text = "Typography",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val fontPrefs = context.getSharedPreferences("viz_prefs", Context.MODE_PRIVATE)
                var fontExpanded by remember { mutableStateOf(false) }
                var selectedFont by remember {
                    mutableStateOf(fontPrefs.getString("selected_font", "NDot") ?: "NDot")
                }
                @OptIn(ExperimentalMaterial3Api::class)
                ExposedDropdownMenuBox(
                    expanded = fontExpanded,
                    onExpandedChange = { fontExpanded = !fontExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedFont,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fontExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = fontExpanded,
                        onDismissRequest = { fontExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("NDot") },
                            onClick = {
                                coroutineScope.launch {
                                    fontPrefs.edit().putString("selected_font", "NDot").apply()
                                    selectedFont = "NDot"
                                }
                                fontExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("NType") },
                            onClick = {
                                coroutineScope.launch {
                                    fontPrefs.edit().putString("selected_font", "NType").apply()
                                    selectedFont = "NType"
                                }
                                fontExpanded = false
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Toggle between NDot and NType fonts app-wide.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color.Gray
                )
            }

        }

        BodyText(text = "More settings coming soon...")
        Spacer(modifier = Modifier.height(28.dp))
    }
}
