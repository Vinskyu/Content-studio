package com.contentstudio.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.BootstrapInstaller
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.ActionSolidBg
import com.contentstudio.app.ui.theme.ActionSolidText
import com.contentstudio.app.ui.theme.BgDark
import com.contentstudio.app.ui.theme.BorderSubtle
import com.contentstudio.app.ui.theme.CardHighlight
import com.contentstudio.app.ui.theme.ErrorCardBg
import com.contentstudio.app.ui.theme.ErrorCardBorder
import com.contentstudio.app.ui.theme.ErrorCardText
import com.contentstudio.app.ui.theme.ProgressBarError
import com.contentstudio.app.ui.theme.ProgressBarFill
import com.contentstudio.app.ui.theme.ProgressBarTrack
import com.contentstudio.app.ui.theme.SurfaceCard
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import java.io.File

@Composable
fun SetupScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val stage by LinuxRuntimeManager.setupStage.collectAsState()
    val message by LinuxRuntimeManager.setupMessage.collectAsState()
    val logs by LinuxRuntimeManager.logs.collectAsState()
    val setupError by LinuxRuntimeManager.setupError.collectAsState()

    var showLogs by remember { mutableStateOf(false) }
    var copiedLog by remember { mutableStateOf(false) }
    val logListState = rememberLazyListState()

    val isAlreadyInstalled = remember {
        val paths = BootstrapInstaller.getPaths(context)
        BootstrapInstaller.isBootstrapInstalled(context) &&
            File(paths.prefixDir, "bin/node").exists()
    }

    val progressAnim = remember { Animatable(0f) }
    val clampedStage = stage.coerceIn(1, 4)
    val targetProgress = (clampedStage / 4f).coerceIn(0f, 1f)

    LaunchedEffect(targetProgress) {
        progressAnim.animateTo(
            targetValue = targetProgress,
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
        )
    }

    val currentProgress = progressAnim.value
    val displayPct = (currentProgress * 100).toInt().coerceIn(0, 100)

    val infiniteTransition = rememberInfiniteTransition(label = "setup_shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fill_shimmer"
    )

    val slidingPillFraction by infiniteTransition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sliding_pill"
    )

    LaunchedEffect(logs.size) {
        if (showLogs && logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.size - 1)
        }
    }

    val isBatteryExempt = remember {
        val pm = context.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONTENT STUDIO",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                if (!isAlreadyInstalled) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$displayPct%",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(CardHighlight)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "[$clampedStage/4]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            Text(
                text = if (isAlreadyInstalled) {
                    "Menyalakan layanan lokal..."
                } else {
                    "Menyiapkan lingkungan Linux lokal, Node.js, Python, dan Gemini CLI (hanya sekali saat pemasangan pertama)."
                },
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                color = TextSecondary
            )

            // Progress Bar Track (Flat tipis 4.dp #232328)
            if (isAlreadyInstalled) {
                // Mode startup cepat: indeterminate sliding pill halus
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ProgressBarTrack)
                ) {
                    val trackWidth = maxWidth
                    val pillWidth = trackWidth * 0.35f
                    val xOffset = trackWidth * slidingPillFraction
                    Box(
                        modifier = Modifier
                            .offset(x = xOffset)
                            .width(pillWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (setupError != null) ProgressBarError else ProgressBarFill
                            )
                    )
                }
            } else {
                // Mode instalasi pertama: meluncur mulus dari 0f via Animatable + shimmer halus
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ProgressBarTrack)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(currentProgress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (setupError != null) {
                                    ProgressBarError
                                } else {
                                    ProgressBarFill.copy(alpha = if (currentProgress < 1f) shimmerAlpha else 1f)
                                }
                            )
                    )
                }
            }

            Text(
                text = if (isAlreadyInstalled && setupError == null) "Menyalakan layanan lokal..." else message,
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )

            if (setupError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(ErrorCardBg)
                        .border(1.dp, ErrorCardBorder, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "KENDALA SISTEM",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ErrorCardText
                    )
                    Text(
                        text = setupError!!,
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = ErrorCardText
                    )
                }
            }

            // Tombol lipat log terminal
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                    .clickable { showLogs = !showLogs }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Log Terminal (${logs.size} baris)",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (copiedLog) "[TERSALIN]" else "[SALIN]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextPrimary,
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                            copiedLog = true
                        }
                    )
                    Text(
                        text = if (showLogs) "[TUTUP]" else "[LIHAT]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                }
            }

            AnimatedVisibility(visible = showLogs) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BgDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isBatteryExempt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle))
                    ) {
                        Text(
                            text = "IZINKAN BACKGROUND",
                            fontFamily = FontFamily.Default,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (setupError != null) {
                    Button(
                        onClick = { LinuxRuntimeManager.startSetup(context, forceRetry = true) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ActionSolidBg,
                            contentColor = ActionSolidText
                        )
                    ) {
                        Text(
                            text = "ULANGI",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
