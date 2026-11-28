package com.contentstudio.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.Text
import kotlinx.coroutines.delay

/**
 * Satu baris kronologi alur pikir agen.
 *
 * kind == "thought" -> narasi pertimbangan (redup)
 * kind == "step"    -> aksi alat konkret (lebih terang)
 */
data class ThinkingEntry(
    val kind: String,
    val text: String,
    val timestampMs: Long
)

private val ThinkSurface = Color(0xFF15171C)
private val ThinkLine = Color(0xFF2A2F3A)
private val ThinkMuted = Color(0xFF8A919E)
private val ThinkStep = Color(0xFFC5CBD6)
private val ThinkPrimary = Color(0xFFF2F4F8)

/**
 * Blok live thinking ala Claude: terbuka & mengalir saat agen berpikir,
 * lalu melipat otomatis menjadi satu baris ringkas saat jawaban mulai mengalir.
 */
@Composable
fun StudioThinkingBlock(
    entries: List<ThinkingEntry>,
    isThinking: Boolean,
    startTimeMs: Long?,
    modifier: Modifier = Modifier
) {
    var manualExpanded by remember { mutableStateOf<Boolean?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isThinking) {
        // Setiap transisi status mengembalikan kontrol ke perilaku otomatis.
        manualExpanded = null
    }

    LaunchedEffect(startTimeMs, isThinking) {
        val start = startTimeMs ?: return@LaunchedEffect
        if (!isThinking) {
            elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            return@LaunchedEffect
        }
        while (true) {
            elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            delay(250)
        }
    }

    val expanded = manualExpanded ?: isThinking
    val seconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val stepCount = entries.count { it.kind == "step" }
    val metaCount = if (stepCount > 0) stepCount else entries.size
    val headerText = if (isThinking) {
        "Thinking \u00B7 ${seconds}s"
    } else {
        val unit = if (metaCount == 1) "step" else "steps"
        "Thought for ${seconds}s \u00B7 $metaCount $unit"
    }

    val transition = rememberInfiniteTransition(label = "thinkingPulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinkingPulseAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ThinkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { manualExpanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(if (isThinking) pulseAlpha else 0.6f)
                        .clip(CircleShape)
                        .background(ThinkMuted)
                )
                Text(
                    text = headerText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isThinking) ThinkPrimary else ThinkMuted,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Tutup alur pikir" else "Buka alur pikir",
                tint = ThinkMuted,
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ThinkingTimeline(entries = entries, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
        }
    }
}

@Composable
private fun ThinkingTimeline(
    entries: List<ThinkingEntry>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            scrollState.animateScrollTo(Int.MAX_VALUE)
        }
    }

    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(ThinkLine)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(max = 180.dp)
                .verticalScroll(scrollState)
                .padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = "Menyiapkan langkah pertama.",
                    fontSize = 12.5.sp,
                    color = ThinkMuted,
                    lineHeight = 18.sp
                )
            }
            entries.forEach { entry ->
                if (entry.kind == "thought") {
                    Text(
                        text = entry.text,
                        fontSize = 13.sp,
                        color = ThinkMuted,
                        lineHeight = 19.sp
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(ThinkMuted)
                        )
                        Text(
                            text = entry.text,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = ThinkStep,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
