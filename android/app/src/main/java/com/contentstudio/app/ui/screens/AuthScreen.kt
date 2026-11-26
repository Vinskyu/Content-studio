package com.contentstudio.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.ui.theme.ActionSolidBg
import com.contentstudio.app.ui.theme.ActionSolidText
import com.contentstudio.app.ui.theme.BgDark
import com.contentstudio.app.ui.theme.BorderSubtle
import com.contentstudio.app.ui.theme.CardHighlight
import com.contentstudio.app.ui.theme.ErrorCardBg
import com.contentstudio.app.ui.theme.ErrorCardBorder
import com.contentstudio.app.ui.theme.ErrorCardText
import com.contentstudio.app.ui.theme.InputContainerBorder
import com.contentstudio.app.ui.theme.SurfaceCard
import com.contentstudio.app.ui.theme.TextMuted
import com.contentstudio.app.ui.theme.TextPrimary
import com.contentstudio.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun extractLastAuthError(health: JSONObject?): String? {
    if (health == null || health.isNull("last_auth_error")) return null
    val err = health.optString("last_auth_error", "")
    return if (err.isNotBlank() && err != "null") err else null
}

@Composable
fun AuthScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var isStartingAuth by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var isSubmittingCode by remember { mutableStateOf(false) }
    var showCodePanel by remember { mutableStateOf(false) }
    var authCodeInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var lastAuthError by remember { mutableStateOf<String?>(null) }
    var copiedError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val h = withContext(Dispatchers.IO) { LinuxRuntimeManager.refreshHealth() }
        lastAuthError = extractLastAuthError(h)
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CardHighlight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "Antigravity CLI",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "AUTENTIKASI ANTIGRAVITY",
                    fontFamily = FontFamily.Default,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Masuk menggunakan akun Google Anda via browser perangkat. Token disimpan secara lokal tanpa API Key manual.",
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }

            if (statusMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(BgDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
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

            if (lastAuthError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(ErrorCardBg)
                        .border(1.dp, ErrorCardBorder, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DETAIL ERROR OAUTH",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ErrorCardText
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(CardHighlight)
                                .border(1.dp, ErrorCardBorder, RoundedCornerShape(4.dp))
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(lastAuthError!!))
                                    copiedError = true
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (copiedError) "Copied" else "Copy",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ErrorCardText
                            )
                        }
                    }
                    Text(
                        text = lastAuthError!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = ErrorCardText
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (!isStartingAuth) {
                            isStartingAuth = true
                            lastAuthError = null
                            copiedError = false
                            statusMessage = "Menyiapkan tautan login Antigravity..."
                            scope.launch(Dispatchers.IO) {
                                val res = LinuxRuntimeManager.startOAuthLogin()
                                withContext(Dispatchers.Main) {
                                    isStartingAuth = false
                                    val st = res.optString("status")
                                    val authUrl = res.optString("auth_url")
                                    when {
                                        st == "already_authenticated" || st == "already_logged_in" -> {
                                            statusMessage = "Sesi autentikasi aktif."
                                            LinuxRuntimeManager.refreshHealth()
                                        }
                                        st == "ok" && authUrl.isNotBlank() -> {
                                            showCodePanel = true
                                            statusMessage = "Browser terbuka. Selesaikan login lalu tempel kode autentikasi di bawah atau tekan Cek Status Login."
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
                                            statusMessage = res.optString("error", "Gagal memulai login Antigravity.")
                                        }
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isStartingAuth,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
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

                // Panel tempel kode autentikasi dari browser
                if (showCodePanel) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(BgDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Tempel kode autentikasi dari browser (jika diminta)",
                            fontFamily = FontFamily.Default,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(SurfaceCard)
                                    .border(1.dp, InputContainerBorder, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 10.dp, vertical = 9.dp)
                            ) {
                                if (authCodeInput.isEmpty()) {
                                    Text(
                                        text = "Kode otorisasi...",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                BasicTextField(
                                    value = authCodeInput,
                                    onValueChange = { authCodeInput = it },
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = TextPrimary
                                    ),
                                    cursorBrush = SolidColor(TextPrimary),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            val canSubmitCode = authCodeInput.isNotBlank() && !isSubmittingCode
                            Button(
                                onClick = {
                                    if (canSubmitCode) {
                                        isSubmittingCode = true
                                        lastAuthError = null
                                        statusMessage = "Mengirim kode autentikasi..."
                                        scope.launch(Dispatchers.IO) {
                                            val res = LinuxRuntimeManager.submitAuthCode(authCodeInput)
                                            withContext(Dispatchers.Main) {
                                                isSubmittingCode = false
                                                val ok = res.optBoolean("authenticated", false) || res.optString("status") == "ok"
                                                if (ok) {
                                                    statusMessage = "Autentikasi berhasil!"
                                                    LinuxRuntimeManager.refreshHealth()
                                                } else {
                                                    val err = res.optString("error", "Kode autentikasi gagal dikonfirmasi.")
                                                    lastAuthError = err
                                                    statusMessage = "Gagal memverifikasi kode."
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = canSubmitCode,
                                shape = RoundedCornerShape(4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (canSubmitCode) ActionSolidBg else CardHighlight,
                                    contentColor = if (canSubmitCode) ActionSolidText else TextMuted,
                                    disabledContainerColor = CardHighlight,
                                    disabledContentColor = TextMuted
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isSubmittingCode) "PROSES..." else "KONFIRMASI KODE",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        if (!isChecking) {
                            isChecking = true
                            copiedError = false
                            scope.launch(Dispatchers.IO) {
                                val h = LinuxRuntimeManager.refreshHealth()
                                withContext(Dispatchers.Main) {
                                    isChecking = false
                                    val ok = h?.optBoolean("authenticated", false) ?: (h?.optBoolean("logged_in", false) ?: false)
                                    val err = extractLastAuthError(h)
                                    lastAuthError = err
                                    if (!ok) {
                                        statusMessage = if (err != null) {
                                            "Autentikasi belum berhasil. Periksa detail error di atas."
                                        } else {
                                            "Belum terautentikasi. Pastikan proses login di browser sudah selesai."
                                        }
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isChecking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
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
}
