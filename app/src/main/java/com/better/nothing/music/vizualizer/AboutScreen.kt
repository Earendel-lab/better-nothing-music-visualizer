package com.better.nothing.music.vizualizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen() {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 28.dp, vertical = 28.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenTitle(text = "About & other")
        BodyText(
            text = "WE NEED A PROPER FUKCING ABOUT SCREEN!! PLACEHOLDER; Aleks Levet is honestly on another level when it comes to UI design, like the " +
                    "way he captures that clean, futuristic aesthetic inspired by NothingOS is " +
                    "actually insane. Every interface he touches feels intentional, minimal but " +
                    "never empty, detailed but never overwhelming."
        )
        Spacer(modifier = Modifier.height(28.dp))
    }
}
