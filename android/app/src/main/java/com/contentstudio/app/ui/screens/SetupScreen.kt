package com.contentstudio.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.*

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
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
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
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CardHighlight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "[$stage/4]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            Text(
                text = "Menyiapkan lingkungan Linux lokal, Node.js, Python, dan Gemini CLI (hanya sekali saat pemasangan pertama).",
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                color = TextSecondary
            )

            LinearProgressIndicator(
                progress = { (stage.coerceIn(1, 4)) / 4f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = ActionSolidBg,
                trackColor = BorderSubtle
            )

            Text(
                text = message,
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )

            if (setupError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Kendala: $setupError",
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }

            // Tombol lipat log terminal
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                    .clickable { showLogs = !showLogs }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
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
                        shape = RoundedCornerShape(8.dp),
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
                        shape = RoundedCornerShape(8.dp),
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
