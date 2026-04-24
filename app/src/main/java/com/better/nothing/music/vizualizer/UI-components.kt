package com.better.nothing.music.vizualizer

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Vibration
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
        shape    = RoundedCornerShape(8.dp),
        colors   = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFDBDBDB),
            selectedLabelColor     = Color(0xFF000000),
            containerColor         = Color(0xFF000000),
            labelColor             = Color(0xFF727272),
        ),
        border   = FilterChipDefaults.filterChipBorder(
            enabled             = true,
            selected            = selected,
            borderColor         = Color(0xFF727272),
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
    visibleTabs: List<Tab>,
    onTabSelected: (Tab) -> Unit,
) {
    NavigationBar(
        modifier = Modifier
            .height(64.dp)
            .navigationBarsPadding()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0)
    ) {
        visibleTabs.forEach { tab ->
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
                            Tab.Glyphs -> Icons.Filled.GraphicEq
                            Tab.Haptics -> Icons.Filled.Vibration
                            Tab.Settings -> Icons.Filled.Settings
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
    modifier: Modifier = Modifier,
    enableHaptics: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current

    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isActive = isPressed || isDragged

    // Trigger haptic on Press/Release
    LaunchedEffect(isActive) {
        if (isActive) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    // The "Expressive" factor (1.0 to 1.8)
    val animationFactor by animateFloatAsState(
        targetValue = if (isActive) 2.1f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "expressive_bounce"
    )

    val previousValue = remember { mutableIntStateOf(value.toInt()) }

    Slider(
        value = value,
        onValueChange = { newValue ->
            onValueChange(newValue)
            if (enableHaptics && newValue.toInt() != previousValue.intValue) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                previousValue.intValue = newValue.toInt()
            }
        },
        valueRange = valueRange,
        interactionSource = interactionSource,
        modifier = modifier.height(56.dp), // Extra height for the "bloom"
        thumb = {
            // THUMB: Gets THINNER as animationFactor increases
            // Width: 4dp -> 2dp | Height: 44dp -> 48dp
            val thumbWidth = 4.dp / animationFactor

            Box(
                modifier = Modifier
                    .size(width = thumbWidth, height = 44.dp * (animationFactor * 0.8f).coerceAtLeast(1f))
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(2.dp) // Keeps same corner radius
                    )
            )
        },
        track = { sliderState ->
            // TRACK: Gets THICKER
            // Radius: We want it to look like a pill when thin, but less rounded when thick
            val trackHeight = 16.dp * animationFactor

            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier
                    .height(trackHeight),
                thumbTrackGapSize = 4.dp,
                trackInsideCornerSize = 2.dp,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color(0xFF1C1B1B)
                )
            )
        }
    )
}
val NTypeFontFamily = FontFamily(
    Font(R.font.ntype82)
)

val NDotFontFamily = FontFamily(
    Font(resId = R.font.ndot57, weight = FontWeight.Normal)
)

val NDot55FontFamily = FontFamily(
    Font(resId = R.font.ndot55, weight = FontWeight.Normal)
)

@Immutable
data class AppSpacing(
    val edge: Dp = 6.dp,       // Global screen side padding
    val between: Dp = 12.dp,    // Vertical space between cards
    val inner: Dp = 20.dp,      // Padding inside cards (Expressive style)
    val buttonGap: Dp = 4.dp    // Gap between connected buttons
)

val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }

@Composable
fun BetterVizTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("viz_prefs", Context.MODE_PRIVATE)
    val themeName = prefs.getString("selected_theme", "Normal") ?: "Normal"
    val isNothingRed = themeName == "Nothing Red"
    val fontName = prefs.getString("selected_font", "NDot") ?: "NDot"
    val useNType = fontName == "NType"

    val colorScheme = if (isNothingRed) {
        androidx.compose.material3.darkColorScheme(
            background = Color(0xFF000000),
            surface = Color(0xFF0A0A0A),
            primary = Color(0xFFEF4444),
            secondary = Color(0xFFEF4444),
            onBackground = Color(0xFFF5F5F5),
            onSurface = Color(0xFFF5F5F5),
            onPrimary = Color(0xFF000000),
            surfaceVariant = Color(0xFF111111),
            onSecondary = Color(0xFF000000)
        )
    } else {
        androidx.compose.material3.darkColorScheme(
            background = Color.Black,
            surface = Color(0xFF242222),
            primary = Color(0xFFD8D3DA),
            secondary = Color(0xFFB5F2B6),
            onBackground = Color.White,
            onSurface = Color.White,
            onPrimary = Color(0xFF1C1A1D),
            surfaceVariant = Color(0xFF3D3C41),
            onSecondary = Color.White
        )
    }

    val typography = Typography(
        // HEADERS
        displayLarge = TextStyle(
            fontFamily = if (useNType) NTypeFontFamily else NDot55FontFamily,
            fontSize = 45.sp,
            lineHeight = 55.sp,
            fontWeight = FontWeight.Normal
        ),
        headlineMedium = TextStyle(
            fontFamily = if (useNType) NTypeFontFamily else NDotFontFamily,
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
    CompositionLocalProvider(LocalAppSpacing provides AppSpacing()) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = colorScheme,
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
}