package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Shared Quantanium motion tokens and micro-interaction primitives designed for crisp 120Hz fluidity.
 */
object QuantaniumMotion {
    const val QuickDurationMs = 170
    const val StandardDurationMs = 240
    const val EmphasizedDurationMs = 320

    val EaseOutExpo = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    val SpringSnappy = spring<Float>(
        dampingRatio = 0.78f,
        stiffness = 520f
    )

    val SpringGentle = spring<Float>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow
    )

    val SpringBouncy = spring<Float>(
        dampingRatio = 0.68f,
        stiffness = 460f
    )
}

/**
 * Non-intrusive tactile press feedback: scales down subtly on finger-down using the Initial pointer pass
 * so inner `Button`, `clickable`, or `Surface(onClick)` handlers keep normal ripple & click behavior.
 */
fun Modifier.tactilePress(
    enabled: Boolean = true,
    pressedScale: Float = 0.968f
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = QuantaniumMotion.SpringSnappy,
        label = "tactile_press_scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                    }
                } finally {
                    pressed = false
                }
            }
        }
}

/**
 * Tactile spring compression driven by an existing [MutableInteractionSource] (e.g. Material [Surface]).
 */
fun Modifier.tactilePress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.968f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = QuantaniumMotion.SpringSnappy,
        label = "tactile_press_source_scale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Crisp first-appearance entrance (subtle upward glide + fade + micro-scale) with optional stagger index.
 */
fun Modifier.staggeredEntrance(
    index: Int = 0,
    stepDelayMs: Int = 35
): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (index > 0 && stepDelayMs > 0) delay((index.coerceAtMost(8) * stepDelayMs).toLong())
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = QuantaniumMotion.StandardDurationMs, easing = QuantaniumMotion.EaseOutExpo),
        label = "staggered_entrance"
    )
    this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 10f * density
        val s = 0.985f + 0.015f * progress
        scaleX = s
        scaleY = s
    }
}

/** Smooth vertical + opacity transition for live CLI logs, usage panels, and inline banners. */
@Composable
fun QuantaniumExpandableContent(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(QuantaniumMotion.StandardDurationMs, easing = QuantaniumMotion.EaseOutExpo)) +
            expandVertically(tween(QuantaniumMotion.StandardDurationMs, easing = QuantaniumMotion.EaseOutExpo)),
        exit = fadeOut(tween(QuantaniumMotion.QuickDurationMs)) +
            shrinkVertically(tween(QuantaniumMotion.QuickDurationMs)),
        content = content
    )
}

/** Compact status pill with optional breathing status dot. */
@Composable
fun QuantaniumStatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false
) {
    val dotAlpha = if (pulsing) {
        rememberInfiniteTransition(label = "chip_pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "chip_dot_alpha"
        ).value
    } else 1f

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1
            )
        }
    }
}
