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
        CreditEntry("Aleks-Levet", stringResource(R.string.credit_alekslevet_role), "Aleks-Levet"),
        CreditEntry("rKyzen (aka Shivank Dan)", stringResource(R.string.credit_rkyzen_role), "rKyzen"),
        CreditEntry("Oliver Lebaigue", stringResource(R.string.credit_oliver_role), githubUsername = "oliver-leabigue-bright-bench"),
        CreditEntry("Nicouschulas", stringResource(R.string.credit_nicouschulas_role), "Nicouschulas"),
        CreditEntry("SebiAi", stringResource(R.string.credit_sebiai_role), "SebiAi"),
        CreditEntry("Earnedel-lab", stringResource(R.string.credit_earnedel_role), "Earnedel-lab"),
        CreditEntry("あけ なるかみ", stringResource(R.string.credit_ake_role), null),
        CreditEntry("Interlastic", stringResource(R.string.credit_interlastic_role), "Interlastic"),
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
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    uriHandler.openUri("https://github.com/Aleks-Levet/better-nothing-music-visualizer")
                },
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.github_repository),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.view_source),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFAAAAAA),
                )
            }
        }

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
                    text = stringResource(R.string.version_info, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                BodyText(
                    text = stringResource(R.string.media_projection_info),
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
            BodyText(text = stringResource(R.string.credits), size = 20.sp)
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
                                    text = stringResource(R.string.open_github_profile),
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
