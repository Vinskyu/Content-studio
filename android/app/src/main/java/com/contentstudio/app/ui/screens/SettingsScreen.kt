package com.contentstudio.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.contentstudio.app.data.PythonHost
import com.contentstudio.app.service.AgentService
import com.contentstudio.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var groqKey by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    var openRouterKey by remember { mutableStateOf("") }
    var outDir by remember { mutableStateOf("/sdcard/ContentStudio/out") }

    var storageFreeGb by remember { mutableStateOf("0.0") }
    var storageTotalGb by remember { mutableStateOf("0.0") }
    var storagePctUsed by remember { mutableStateOf("0.0") }
    var saveStatus by remember { mutableStateOf<String?>(null) }

    var isStoragePermitted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        )
    }

    LaunchedEffect(Unit) {
        val keys = PythonHost.getApiKeys(context)
        groqKey = keys["GROQ_API_KEY"] ?: ""
        geminiKey = keys["GEMINI_API_KEY"] ?: ""
        openRouterKey = keys["OPENROUTER_API_KEY"] ?: ""
        outDir = keys["out_dir"] ?: "/sdcard/ContentStudio/out"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isStoragePermitted = Environment.isExternalStorageManager()
        }

        scope.launch(Dispatchers.IO) {
            try {
                val state = PythonHost.getSessionState(context)
                val st = state.optJSONObject("storage")
                if (st != null) {
                    withContext(Dispatchers.Main) {
                        storageFreeGb = st.optDouble("free_gb", 0.0).toString()
                        storageTotalGb = st.optDouble("total_gb", 0.0).toString()
                        storagePctUsed = st.optDouble("pct_used", 0.0).toString()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "SETELAN SISTEM & BYOK",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        // KARTU PENYIMPANAN
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "PENYIMPANAN PERANGKAT",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = "Bebas: $storageFreeGb GB / Total: $storageTotalGb GB ($storagePctUsed% terpakai)",
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                color = TextSecondary
            )

            if (isStoragePermitted) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CardHighlight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "[IZIN STORAGE: AKTIF]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            } else {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                context.startActivity(intent)
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActionSolidBg,
                        contentColor = ActionSolidText
                    )
                ) {
                    Text(
                        text = "BERIKAN IZIN STORAGE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // KARTU API KEYS BYOK
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceCard)
                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "BYOK API KEYS (LLM ROUTER)",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            MaskedApiKeyInputField(
                label = "GROQ API KEY (Utama / JSON Fast):",
                value = groqKey,
                onValueChange = { groqKey = it }
            )

            MaskedApiKeyInputField(
                label = "GEMINI API KEY (Vision / Multimodal):",
                value = geminiKey,
                onValueChange = { geminiKey = it }
            )

            MaskedApiKeyInputField(
                label = "OPENROUTER API KEY (Free Large Model):",
                value = openRouterKey,
                onValueChange = { openRouterKey = it }
            )

            PlainInputField(
                label = "FOLDER OUTPUT VIDEO:",
                value = outDir,
                onValueChange = { outDir = it }
            )

            Button(
                onClick = {
                    PythonHost.saveApiKeys(context, groqKey, geminiKey, openRouterKey, outDir)
                    AgentService.refreshSessionState(context)
                    saveStatus = "[OK] Setelan tersimpan."
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActionSolidBg,
                    contentColor = ActionSolidText
                )
            ) {
                Text(
                    text = "SIMPAN SETELAN",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (saveStatus != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(CardHighlight)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = saveStatus!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun MaskedApiKeyInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TextSecondary
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardHighlight)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                    .clickable { isVisible = !isVisible }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isVisible) "[TUTUP]" else "[LIHAT]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextPrimary
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BgDark)
                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TextPrimary
                ),
                cursorBrush = SolidColor(TextPrimary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun PlainInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextSecondary
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BgDark)
                .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TextPrimary
                ),
                cursorBrush = SolidColor(TextPrimary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
