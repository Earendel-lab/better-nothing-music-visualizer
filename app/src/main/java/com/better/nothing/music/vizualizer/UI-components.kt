package com.better.nothing.music.vizualizer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScreenTitle(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.displayLarge,
        color = Color.White,
    )
}

@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    lineHeight: TextUnit = 24.sp,
) {
    Text(
        text  = text,
        // Hoist TextStyle out of every recomposition; only reallocated when
        // size or lineHeight actually changes.
        style = remember(size, lineHeight) {
            TextStyle(
                fontSize   = size,
                lineHeight = lineHeight,
                fontWeight = FontWeight.Normal,
            )
        },
        color    = Color(0xFFB8B8B8),
        modifier = modifier,
    )
}

@Composable
fun NativeFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick  = onClick,
        label    = { Text(text = label, style = MaterialTheme.typography.labelLarge) },
        colors   = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFD8D3DA),
            selectedLabelColor     = Color(0xFF1E1B20),
            containerColor         = Color(0xFF5A565A),
            labelColor             = Color(0xFFE7E0E7),
        ),
        border   = FilterChipDefaults.filterChipBorder(
            enabled             = true,
            selected            = selected,
            borderColor         = Color.Transparent,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun StartStopButton(
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed         by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue   = if (isPressed) 0.9f else 1.1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessLow
        ),
        label = "buttonScale"
    )

    val containerColor by animateColorAsState(
        targetValue   = if (running) Color(0xFFE53935) else Color(0xFFB5F2B6),
        animationSpec = tween(600, easing = EaseInOutCubic),
        label         = "containerColor"
    )

    val contentColor by animateColorAsState(
        targetValue   = if (running) Color.White else Color(0xFF1C5A21),
        animationSpec = tween(600, easing = EaseInOutCubic),
        label         = "contentColor"
    )

    FloatingActionButton(
        onClick           = onClick,
        interactionSource = interactionSource,
        shape             = RoundedCornerShape(15.dp),
        modifier          = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .height(56.dp)
            .padding(5.dp),
        containerColor = containerColor,
        contentColor   = contentColor,
    ) {
        Row(
            modifier             = Modifier.padding(horizontal = 16.dp),
            verticalAlignment    = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnimatedContent(
                targetState  = running,
                transitionSpec = { (scaleIn() + fadeIn()).togetherWith(scaleOut() + fadeOut()) },
                label        = "iconTransition"
            ) { isRunning ->
                Icon(
                    imageVector     = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier        = Modifier.size(24.dp)
                )
            }
            Text(
                text  = if (running) "Stop visualizer" else "Start visualizer",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight    = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            )
        }
    }
}

@Composable
fun NativeBottomBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
) {
    val tabs = Tab.all

    NavigationBar(
        modifier = Modifier.height(64.dp), // The magic number
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0) // Prevents extra padding on gesture-nav phones
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selectedTab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                label = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                icon = {
                    Icon(
                        imageVector = when (tab) {
                            Tab.Audio -> Icons.AutoMirrored.Filled.VolumeUp
                            Tab.Glyphs -> Icons.Filled.Settings
                            Tab.About -> Icons.Filled.Info
                        },
                        contentDescription = tab.label
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    selectedIconColor = MaterialTheme.colorScheme.onBackground,
                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    // Must be remembered — a new object every recompose breaks press tracking.
    val interactionSource = remember { MutableInteractionSource() }

    Slider(
        value             = value,
        onValueChange     = onValueChange,
        valueRange        = valueRange,
        interactionSource = interactionSource,
        modifier          = modifier.height(48.dp),
        thumb = {
            Spacer(
                modifier = Modifier
                    .size(width = 4.dp, height = 44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onBackground,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState          = sliderState,
                modifier             = Modifier.height(16.dp),
                thumbTrackGapSize    = 4.dp,
                trackInsideCornerSize = 2.dp,
                colors               = SliderDefaults.colors(
                    activeTrackColor   = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    )
}

val NDotFontFamily = FontFamily(
    Font(resId = R.font.ndot57, weight = FontWeight.Normal)
    // If ndot55 is your lighter variant, you could add it here as FontWeight.Light
)

val NDot55FontFamily = FontFamily(
    Font(resId = R.font.ndot55, weight = FontWeight.Normal)
    // If ndot55 is your lighter variant, you could add it here as FontWeight.Light
)

@Composable
fun BetterVizTheme(content: @Composable () -> Unit) {
    val typography = Typography(
        // HEADERS
        displayLarge = TextStyle(
            fontFamily = NDot55FontFamily,
            fontSize = 45.sp,
            lineHeight = 55.sp,
            fontWeight = FontWeight.Normal
        ),
        headlineMedium = TextStyle(
            fontFamily = NDotFontFamily,
            fontSize = 30.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Normal
        ),

        // SUB-HEADERS
        titleLarge = TextStyle(
            fontSize = 21.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Normal
        ),
        titleMedium = TextStyle(
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal
        ),

        // BODY & LABELS (Keep system font for high legibility at small sizes)
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium
        ),
        labelMedium = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium
        ),
    )

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Black,
            surface = Color(0xFF242222),
            primary = Color(0xFFD8D3DA),
            secondary = Color(0xFFB5F2B6),
            onBackground = Color.White,
            onSurface = Color.White,
            onPrimary = Color(0xFF1C1A1D),
            surfaceVariant = Color(0xFF3D3C41),
        ),
        shapes = Shapes(
            extraLarge = RoundedCornerShape(32.dp),
            large = RoundedCornerShape(28.dp),
            medium = RoundedCornerShape(20.dp),
            small = RoundedCornerShape(14.dp),
        ),
        typography = typography,
        content = content,
    )
}