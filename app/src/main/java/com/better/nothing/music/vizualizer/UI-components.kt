@file:OptIn(ExperimentalMaterial3Api::class)

package com.better.nothing.music.vizualizer

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
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
        color = MaterialTheme.colorScheme.onBackground,
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
        color    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        modifier = modifier,
    )
}

@Composable
fun NativeFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    FilterChip(
        selected = selected,
        onClick  = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        },
        label    = { Text(text = label, style = MaterialTheme.typography.labelLarge) },
        shape    = RoundedCornerShape(8.dp),
        colors   = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor     = MaterialTheme.colorScheme.onPrimary,
            containerColor         = Color.Transparent,
            labelColor             = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border   = FilterChipDefaults.filterChipBorder(
            enabled             = true,
            selected            = selected,
            borderColor         = MaterialTheme.colorScheme.outline,
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
    val haptics           = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue   = if (isPressed) 0.9f else 1.1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessLow
        ),
        label = "buttonScale"
    )

    val containerColor by animateColorAsState(
        targetValue   = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        animationSpec = tween(600, easing = EaseInOutCubic),
        label         = "containerColor"
    )

    val contentColor by animateColorAsState(
        targetValue   = if (running) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondary,
        animationSpec = tween(600, easing = EaseInOutCubic),
        label         = "contentColor"
    )

    FloatingActionButton(
        onClick           = {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            onClick()
        },
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
            .heightIn(min = 80.dp)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        windowInsets = NavigationBarDefaults.windowInsets
    ) {
        visibleTabs.forEach { tab ->
            val isSelected = tab == selectedTab
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (!isSelected) {
                        onTabSelected(tab)
                    }
                },
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
                    indicatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedIconColor = MaterialTheme.colorScheme.onBackground,
                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface
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
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current

    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isActive = isPressed || isDragged

    val wasActive = remember { mutableStateOf(false) }

    // Trigger haptic on Press/Release (skip initial state)
    LaunchedEffect(isActive) {
        if (!wasActive.value && isActive) {
            // Trigger on press (transition from false to true)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        } else if (wasActive.value && !isActive) {
            // Trigger on release (transition from true to false)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
        wasActive.value = isActive
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

    val view = LocalView.current
    Slider(
        value = value,
        onValueChange = { newValue ->
            onValueChange(newValue)
        },
        valueRange = valueRange,
        interactionSource = interactionSource,
        modifier = modifier
            .height(56.dp)
            .pointerInput(isActive) {
                if (isActive) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                }
            }
            .pointerInteropFilter { motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            },
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
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    )
}

@Composable
fun ExpressiveRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val startInteractionSource = remember { MutableInteractionSource() }
    val endInteractionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current

    val startActive by startInteractionSource.collectIsPressedAsState()
    val startDragged by startInteractionSource.collectIsDraggedAsState()
    val endActive by endInteractionSource.collectIsPressedAsState()
    val endDragged by endInteractionSource.collectIsDraggedAsState()

    val isAnyActive = startActive || startDragged || endActive || endDragged

    // Animation and Haptic logic remains the same...
    val animationFactor by animateFloatAsState(
        targetValue = if (isAnyActive) 2.1f else 1.0f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "track_bloom"
    )

    val startThumbFactor by animateFloatAsState(if (startActive || startDragged) 2.1f else 1.0f)
    val endThumbFactor by animateFloatAsState(if (endActive || endDragged) 2.1f else 1.0f)

    LaunchedEffect(isAnyActive) {
        if (isAnyActive) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    val view = LocalView.current
    RangeSlider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        startInteractionSource = startInteractionSource,
        endInteractionSource = endInteractionSource,
        modifier = modifier
            .height(64.dp)
            .pointerInput(isAnyActive) {
                if (isAnyActive) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                }
            }
            .pointerInteropFilter { motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            },
        startThumb = { ExpressiveThumb(factor = startThumbFactor) },
        endThumb = { ExpressiveThumb(factor = endThumbFactor) },
        track = { rangeSliderState ->
            val trackHeight = 12.dp * animationFactor
            SliderDefaults.Track(
                rangeSliderState = rangeSliderState,
                modifier = Modifier.height(trackHeight),
                thumbTrackGapSize = 4.dp,
                drawStopIndicator = null,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    )
}

@Composable
private fun ExpressiveThumb(factor: Float) {
    // The thumb gets thinner and taller when grabbed
    val thumbWidth = 4.dp / factor
    val thumbHeight = 40.dp * (factor * 0.8f).coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .size(width = thumbWidth, height = thumbHeight)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(2.dp)
            )
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
fun BetterVizTheme(
    themeName: String = "OLED Black",
    fontName: String = "NDot",
    content: @Composable () -> Unit
) {
    val useNType = fontName == "NType"

    val targetColorScheme = when (themeName) {
        "Nothing Red" -> {
            androidx.compose.material3.darkColorScheme(
                background = Color.Black,
                surface = Color(0xFF0D0D0D),
                primary = Color(0xFFEE0000),    // Authentic Nothing Red
                secondary = Color(0xFFEE0000),
                error = Color(0xFFEE0000),
                onBackground = Color.White,
                onSurface = Color.White,
                onPrimary = Color.White,
                onSecondary = Color.White,
                onError = Color.White,
                surfaceVariant = Color(0xFF1A1A1A),
                onSurfaceVariant = Color(0xFFB3B3B3),
                outline = Color(0xFF333333)
            )
        }
        "Liquorice Black" -> {
            androidx.compose.material3.darkColorScheme(
                background = Color(0xFF0F0F0F), // Authentic Liquorice Black
                surface = Color(0xFF1A1A1A),
                primary = Color(0xFFD8D3DA),
                secondary = Color(0xFFA0FFA3),
                error = Color(0xFFC83B3B),
                onBackground = Color.White,
                onSurface = Color.White,
                onPrimary = Color(0xFF1C1A1D),
                onSecondary = Color(0xFF1C5A21),
                onError = Color.White,
                surfaceVariant = Color(0xFF242424),
                onSurfaceVariant = Color(0xFF676767),
                outline = Color(0xFF2C2C2C)
            )
        }
        "Nothing Light" -> {
            androidx.compose.material3.lightColorScheme(
                background = Color.White,
                surface = Color(0xFFF5F5F5),
                primary = Color(0xFF000000),
                secondary = Color(0xFF626262),
                error = Color(0xFFEE0000),
                onBackground = Color.Black,
                onSurface = Color.Black,
                onPrimary = Color.White,
                onSecondary = Color.White,
                onError = Color.White,
                surfaceVariant = Color(0xFFE0E0E0),
                onSurfaceVariant = Color(0xFF757575),
                outline = Color(0xFFBDBDBD)
            )
        }
        else -> { // OLED Black / Default
            androidx.compose.material3.darkColorScheme(
                background = Color.Black,
                surface = Color(0xFF1A1A1A),
                primary = Color(0xFFD8D3DA),
                secondary = Color(0xFFA0FFA3),
                error = Color(0xFFC83B3B),
                onBackground = Color.White,
                onSurface = Color.White,
                onPrimary = Color(0xFF1C1A1D),
                onSecondary = Color(0xFF1C5A21),
                onError = Color.White,
                surfaceVariant = Color(0xFF242424),
                onSurfaceVariant = Color(0xFF676767),
                outline = Color(0xFF2C2C2C)
            )
        }
    }

    val colorScheme = targetColorScheme.copy(
        primary = animateColorAsState(targetColorScheme.primary, tween(500)).value,
        onPrimary = animateColorAsState(targetColorScheme.onPrimary, tween(500)).value,
        secondary = animateColorAsState(targetColorScheme.secondary, tween(500)).value,
        onSecondary = animateColorAsState(targetColorScheme.onSecondary, tween(500)).value,
        error = animateColorAsState(targetColorScheme.error, tween(500)).value,
        onError = animateColorAsState(targetColorScheme.onError, tween(500)).value,
        background = animateColorAsState(targetColorScheme.background, tween(500)).value,
        onBackground = animateColorAsState(targetColorScheme.onBackground, tween(500)).value,
        surface = animateColorAsState(targetColorScheme.surface, tween(500)).value,
        onSurface = animateColorAsState(targetColorScheme.onSurface, tween(500)).value,
        surfaceVariant = animateColorAsState(targetColorScheme.surfaceVariant, tween(500)).value,
        onSurfaceVariant = animateColorAsState(targetColorScheme.onSurfaceVariant, tween(500)).value,
        outline = animateColorAsState(targetColorScheme.outline, tween(500)).value,
    )

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
