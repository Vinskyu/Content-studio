package com.contentstudio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.contentstudio.app.data.PythonHost
import com.contentstudio.app.service.AgentService
import com.contentstudio.app.ui.components.AppTab
import com.contentstudio.app.ui.components.FloatingBottomNav
import com.contentstudio.app.ui.screens.AgentScreen
import com.contentstudio.app.ui.screens.ProjectsScreen
import com.contentstudio.app.ui.screens.SettingsScreen
import com.contentstudio.app.ui.theme.BgDark
import com.contentstudio.app.ui.theme.ContentStudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            ContentStudioTheme {
                var selectedTab by remember { mutableStateOf(AppTab.AGENT) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            PythonHost.ensureInitialized(applicationContext)
                            val state = PythonHost.getSessionState(applicationContext)
                            val ws = state.optString("active_workspace", "hok_insights")
                            AgentService.setWorkspace(ws)

                            val providers = state.optJSONObject("providers")
                            val groqSt = providers?.optJSONObject("groq")?.optString("status", "NO KEY") ?: "NO KEY"
                            AgentService.updateApiStatus(groqSt)
                        } catch (_: Exception) {
                            AgentService.updateApiStatus("ERROR")
                        }
                    }
                }

                Scaffold(
                    bottomBar = {
                        FloatingBottomNav(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    },
                    containerColor = BgDark
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BgDark)
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            AppTab.AGENT -> AgentScreen()
                            AppTab.PROJECTS -> ProjectsScreen()
                            AppTab.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}
