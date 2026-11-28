package com.contentstudio.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Flat Matte Dark Color Palette (V3) ───────────────────────────────────────
val StudioBg = StudioDesignSystem.BgCanvas
val StudioSurface = StudioDesignSystem.BgSurface
val StudioSurfaceElevated = StudioDesignSystem.BgElevated
val StudioBorder = StudioDesignSystem.DividerSubtle

val StudioPrimary = Color(0xFFF2F4F8)
val StudioPrimaryText = Color(0xFF0B0C0E)

val StudioSecondary = StudioDesignSystem.BgElevated
val StudioSecondaryText = Color(0xFFF2F4F8)

val StudioDangerBg = Color(0xFF2A1417)
val StudioDangerText = StudioDesignSystem.AccentDanger
val StudioDangerRed = Color(0xFFD96B6B)

val StudioAccent = Color(0xFF5E81AC)
val StudioAccentLight = Color(0xFFE6E9EF)
val StudioSage = Color(0xFF67C28C)
val StudioBadgeGreenBg = Color(0xFF102619)
val StudioBadgeGreenText = Color(0xFF4ADE80)

val TextPrimary = StudioDesignSystem.TextPrimary
val TextSecondary = StudioDesignSystem.TextSecondary
val TextMuted = StudioDesignSystem.TextMuted

// ── Smooth Organic Animation Helpers ────────────────────────────────────────

fun studioLobbyTransition(): ContentTransform {
    return (slideInHorizontally(
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        initialOffsetX = { it / 5 }
    ) + fadeIn(tween(240, easing = FastOutSlowInEasing))) togetherWith (
        slideOutHorizontally(
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            targetOffsetX = { -it / 5 }
        ) + fadeOut(tween(180))
    )
}

fun studioLobbyBackTransition(): ContentTransform {
    return (slideInHorizontally(
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        initialOffsetX = { -it / 5 }
    ) + fadeIn(tween(240, easing = FastOutSlowInEasing))) togetherWith (
        slideOutHorizontally(
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            targetOffsetX = { it / 5 }
        ) + fadeOut(tween(180))
    )
}

fun studioPipelineTransition(isForward: Boolean): ContentTransform {
    return if (isForward) {
        (slideInHorizontally(
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            initialOffsetX = { it / 4 }
        ) + fadeIn(tween(220, easing = FastOutSlowInEasing))) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                targetOffsetX = { -it / 4 }
            ) + fadeOut(tween(180))
        )
    } else {
        (slideInHorizontally(
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            initialOffsetX = { -it / 4 }
        ) + fadeIn(tween(220, easing = FastOutSlowInEasing))) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                targetOffsetX = { it / 4 }
            ) + fadeOut(tween(180))
        )
    }
}

val StudioExpandEnter = fadeIn(tween(180)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = 420f))
val StudioShrinkExit = fadeOut(tween(140)) + shrinkVertically()

// ── Standard UI Components ──────────────────────────────────────────────────

@Composable
fun StudioTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(StudioBg)
            .border(0.5.dp, StudioBorder, RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onBack != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioSecondary)
                            .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.Center) {
                    Text(
                        text = title,
                        fontFamily = FontFamily.Default,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            fontFamily = FontFamily.Default,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions()
                }
            }
        }
    }
}

@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.98f else 1.0f,
        label = "cardScale"
    )

    val baseModifier = modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(RoundedCornerShape(8.dp))
        .background(StudioSurface)
        .animateContentSize()

    val finalModifier = if (onClick != null) {
        baseModifier.clickable(
            interactionSource = interactionSource,
            indication = null
        ) { onClick() }
    } else {
        baseModifier
    }

    Column(
        modifier = finalModifier.padding(14.dp),
        content = content
    )
}

@Composable
fun StudioPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 40.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        label = "primaryBtnScale"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StudioPrimary,
            contentColor = StudioPrimaryText,
            disabledContainerColor = StudioSecondary,
            disabledContentColor = TextMuted
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(height)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StudioSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 40.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        label = "secBtnScale"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StudioSecondary,
            contentColor = StudioSecondaryText,
            disabledContainerColor = StudioSurface,
            disabledContentColor = TextMuted
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(height)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StudioDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 40.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        label = "dangerBtnScale"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StudioDangerBg,
            contentColor = StudioDangerText,
            disabledContainerColor = StudioSurface,
            disabledContentColor = TextMuted
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(height)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Default,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StudioSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isDanger: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        label = "smallBtnScale"
    )

    val bg = when {
        isPrimary -> StudioPrimary
        isDanger -> StudioDangerBg
        else -> StudioSecondary
    }
    val fg = when {
        isPrimary -> StudioPrimaryText
        isDanger -> StudioDangerText
        else -> StudioSecondaryText
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) bg else StudioSurface)
            .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) fg else TextMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StudioSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(StudioSurface)
            .border(0.5.dp, StudioBorder, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = selectedIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) Color(0xFF26262F) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) StudioPrimary else TextMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun StudioSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            fontFamily = FontFamily.Default,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextMuted,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (action != null) {
            action()
        }
    }
}

@Composable
fun StudioBadge(
    text: String,
    modifier: Modifier = Modifier,
    isGreen: Boolean = false,
    isAccent: Boolean = false
) {
    val bg = when {
        isGreen -> StudioBadgeGreenBg
        isAccent -> Color(0xFF142433)
        else -> StudioSecondary
    }
    val fg = when {
        isGreen -> StudioBadgeGreenText
        isAccent -> StudioAccent
        else -> TextSecondary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(0.5.dp, StudioBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun StudioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = TextStyle(
            color = TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Default
        ),
        cursorBrush = SolidColor(StudioPrimary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(StudioSurfaceElevated)
                    .border(0.5.dp, StudioBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                }
                innerTextField()
            }
        }
    )
}
