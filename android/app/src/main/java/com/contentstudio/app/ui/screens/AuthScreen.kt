package com.contentstudio.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AuthScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isStartingAuth by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

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
            Text(
                text = "AUTENTIKASI GEMINI CLI",
                fontFamily = FontFamily.Default,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Text(
                text = "Masuk menggunakan akun Google Anda via browser perangkat. Tidak perlu API Key manual.",
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                color = TextSecondary
            )

            if (statusMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = statusMessage!!,
                        fontFamily = FontFamily.Default,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }

            Button(
                onClick = {
                    if (!isStartingAuth) {
                        isStartingAuth = true
                        statusMessage = "Menyiapkan URL login OAuth Google..."
                        scope.launch(Dispatchers.IO) {
                            val res = LinuxRuntimeManager.startOAuthLogin()
                            withContext(Dispatchers.Main) {
                                isStartingAuth = false
                                val st = res.optString("status")
                                val authUrl = res.optString("auth_url")
                                when {
                                    st == "already_logged_in" -> {
                                        LinuxRuntimeManager.refreshHealth()
                                    }
                                    st == "ok" && authUrl.isNotBlank() -> {
                                        statusMessage = "Browser terbuka. Selesaikan login lalu kembali ke aplikasi dan tekan Cek Status Login."
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            statusMessage = "Gagal membuka browser: ${e.message}"
                                        }
                                    }
                                    else -> {
                                        statusMessage = res.optString("error", "Gagal memulai OAuth login.")
                                    }
                                }
                            }
                        }
                    }
                },
                enabled = !isStartingAuth,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActionSolidBg,
                    contentColor = ActionSolidText
                )
            ) {
                Text(
                    text = if (isStartingAuth) "MENYIAPKAN OAUTH..." else "LOGIN AKUN GOOGLE (OAUTH)",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = {
                    if (!isChecking) {
                        isChecking = true
                        scope.launch(Dispatchers.IO) {
                            val h = LinuxRuntimeManager.refreshHealth()
                            withContext(Dispatchers.Main) {
                                isChecking = false
                                val ok = h?.optBoolean("logged_in", false) ?: false
                                if (!ok) {
                                    statusMessage = "Belum terautentikasi. Pastikan proses login di browser sudah selesai."
                                }
                            }
                        }
                    }
                },
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(BorderSubtle))
            ) {
                Text(
                    text = if (isChecking) "MEMERIKSA..." else "CEK STATUS LOGIN",
                    fontFamily = FontFamily.Default,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
