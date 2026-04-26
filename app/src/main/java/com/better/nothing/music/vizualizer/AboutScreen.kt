package com.better.nothing.music.vizualizer

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutScreen() {
    val scrollState = rememberScrollState()
    val uriHandler = LocalUriHandler.current
    val credits = listOf(
        CreditEntry("Aleks-Levet", "Founder, coordinator, main idea, owner", "Aleks-Levet"),
        CreditEntry("Nicouschulas", "Readme & Wiki enhancements", "Nicouschulas"),
        CreditEntry("rKyzen (aka Shivank Dan)", "Android app developer (real-time music stream)", "rKyzen"),
        CreditEntry("SebiAi", "Glyph modding and support", "SebiAi"),
        CreditEntry("Earnedel-lab", "Readme enhancements", "Earnedel-lab"),
        CreditEntry("あけ なるかみ", "Developer working on a music app with integration", null),
        CreditEntry("Interlastic", "Discord bot for testing the script (deprecated)", "Interlastic"),
        CreditEntry("Oliver Lebaigue", "", null),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        ScreenTitle(text = stringResource(R.string.about_title))

        BodyText(
            text = stringResource(R.string.about_intro)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                BodyText(
                    text = "MediaProjection is used only to power the real-time visualizer. No unnecessary recording or data storage is performed.",
                    size = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BodyText(text = stringResource(R.string.about_section_why), size = 20.sp)
            BodyText(text = stringResource(R.string.about_why_1))
            BodyText(text = stringResource(R.string.about_why_2))
            BodyText(text = stringResource(R.string.about_why_3))
            BodyText(text = stringResource(R.string.about_why_4))
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BodyText(text = "Credits", size = 20.sp)
            credits.forEach { credit ->
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { base ->
                            if (credit.githubUsername != null) {
                                base.clickable {
                                    uriHandler.openUri("https://github.com/${credit.githubUsername}")
                                }
                            } else {
                                base
                            }
                        },
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = credit.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (credit.role.isNotBlank()) {
                            BodyText(
                                text = credit.role,
                                size = 14.sp,
                                lineHeight = 20.sp,
                            )
                        }
                        if (credit.githubUsername != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "@${credit.githubUsername}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Open GitHub profile",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFFAAAAAA),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

private data class CreditEntry(
    val name: String,
    val role: String,
    val githubUsername: String?,
)
