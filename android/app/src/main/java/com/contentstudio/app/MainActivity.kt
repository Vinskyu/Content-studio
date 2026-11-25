package com.contentstudio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.contentstudio.app.runtime.LinuxRuntimeManager
import com.contentstudio.app.runtime.StudioForegroundService
import com.contentstudio.app.ui.screens.AgentScreen
import com.contentstudio.app.ui.screens.AuthScreen
import com.contentstudio.app.ui.screens.SetupScreen
import com.contentstudio.app.ui.theme.BgDark
import com.contentstudio.app.ui.theme.ContentStudioTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        startRuntimeService()
        LinuxRuntimeManager.startSetup(applicationContext)

        setContent {
            ContentStudioTheme {
                val isReady by LinuxRuntimeManager.isReady.collectAsState()
                val isLoggedIn by LinuxRuntimeManager.isLoggedIn.collectAsState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgDark)
                ) {
                    when {
                        !isReady -> SetupScreen()
                        !isLoggedIn -> AuthScreen()
                        else -> AgentScreen()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (LinuxRuntimeManager.isReady.value && !LinuxRuntimeManager.isLoggedIn.value) {
            Thread {
                LinuxRuntimeManager.refreshHealth()
            }.start()
        }
    }

    private fun startRuntimeService() {
        val intent = Intent(this, StudioForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
