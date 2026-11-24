package com.contentstudio.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.contentstudio.app.ContentStudioApp
import com.contentstudio.app.R
import com.contentstudio.app.data.PythonHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class AgentStepItem(
    val tool: String,
    val status: String,
    val detail: String
)

data class ActionCardItem(
    val type: String,
    val title: String,
    val hook: String = "",
    val planPath: String = "",
    val primaryCmd: String = "",
    val path: String = ""
)

data class MessageBubble(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val attachedFiles: List<String> = emptyList(),
    val steps: List<AgentStepItem> = emptyList(),
    val actionCards: List<ActionCardItem> = emptyList(),
    val isRunning: Boolean = false,
    val currentProgress: Int = 0,
    val currentStage: String = ""
)

class AgentService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val NOTIFICATION_ID = 1001

        private val _messages = MutableStateFlow<List<MessageBubble>>(emptyList())
        val messages: StateFlow<List<MessageBubble>> = _messages.asStateFlow()

        private val _isBusy = MutableStateFlow(false)
        val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

        private val _currentWorkspace = MutableStateFlow("hok_insights")
        val currentWorkspace: StateFlow<String> = _currentWorkspace.asStateFlow()

        private val _apiStatus = MutableStateFlow("CHECKING")
        val apiStatus: StateFlow<String> = _apiStatus.asStateFlow()

        fun setWorkspace(ws: String) {
            _currentWorkspace.value = ws
        }

        fun updateApiStatus(st: String) {
            _apiStatus.value = st
        }

        fun clearMessages() {
            _messages.value = emptyList()
        }

        fun refreshSessionState(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val state = PythonHost.getSessionState(context)
                    val activeWs = state.optString("active_workspace")
                    if (activeWs.isNotBlank()) {
                        _currentWorkspace.value = activeWs
                    }
                    val hasKey = state.optBoolean("has_api_key", false)
                    val prov = state.optString("active_provider", "NONE")
                    if (hasKey && prov != "NONE") {
                        _apiStatus.value = "API: $prov"
                    } else {
                        _apiStatus.value = "NO KEY"
                    }
                } catch (_: Exception) {
                    _apiStatus.value = "NO KEY"
                }
            }
        }

        fun executeCommand(context: Context, inputText: String, attachedFiles: List<String> = emptyList()) {
            val intent = Intent(context, AgentService::class.java).apply {
                putExtra("input_text", inputText)
                putStringArrayListExtra("attached_files", ArrayList(attachedFiles))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inputText = intent?.getStringExtra("input_text") ?: return START_NOT_STICKY
        val attachedFiles = intent.getStringArrayListExtra("attached_files") ?: arrayListOf()

        val notification = createNotification("Agent ContentStudio", "Memulai tugas...", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        scope.launch {
            _isBusy.value = true

            val userMsg = MessageBubble(
                isUser = true,
                text = inputText,
                attachedFiles = attachedFiles
            )
            val currentList = _messages.value.toMutableList()
            currentList.add(userMsg)

            val agentMsgId = java.util.UUID.randomUUID().toString()
            val agentPlaceholder = MessageBubble(
                id = agentMsgId,
                isUser = false,
                text = "Sedang menganalisis instruksi...",
                isRunning = true
            )
            currentList.add(agentPlaceholder)
            _messages.value = currentList

            val liveSteps = mutableListOf<AgentStepItem>()

            try {
                val filesJson = JSONArray(attachedFiles).toString()

                val result = PythonHost.runConsole(this@AgentService, inputText, filesJson) { eventObj ->
                    val type = eventObj.optString("type")
                    if (type == "step") {
                        val tool = eventObj.optString("tool")
                        val st = eventObj.optString("status")
                        val detail = eventObj.optString("detail")
                        val existingIdx = liveSteps.indexOfFirst { it.tool == tool }
                        val newItem = AgentStepItem(tool, st, detail)
                        if (existingIdx >= 0) {
                            liveSteps[existingIdx] = newItem
                        } else {
                            liveSteps.add(newItem)
                        }

                        updateAgentBubble(agentMsgId) { bubble ->
                            bubble.copy(steps = liveSteps.toList())
                        }
                    } else if (type == "progress") {
                        val stage = eventObj.optString("stage")
                        val pct = eventObj.optInt("pct", 0)
                        val msg = eventObj.optString("msg")
                        updateNotificationProgress(stage, pct, msg)
                        updateAgentBubble(agentMsgId) { bubble ->
                            bubble.copy(currentProgress = pct, currentStage = "$stage: $msg")
                        }
                    }
                }

                val reply = result.optString("reply", "Selesai.")
                val rawSteps = result.optJSONArray("steps")
                if (rawSteps != null && liveSteps.isEmpty()) {
                    for (i in 0 until rawSteps.length()) {
                        val sObj = rawSteps.getJSONObject(i)
                        liveSteps.add(
                            AgentStepItem(
                                tool = sObj.optString("tool"),
                                status = sObj.optString("status"),
                                detail = sObj.optString("detail")
                            )
                        )
                    }
                }

                val actionCards = mutableListOf<ActionCardItem>()
                val rawCards = result.optJSONArray("action_cards")
                if (rawCards != null) {
                    for (i in 0 until rawCards.length()) {
                        val cObj = rawCards.getJSONObject(i)
                        actionCards.add(
                            ActionCardItem(
                                type = cObj.optString("type"),
                                title = cObj.optString("title"),
                                hook = cObj.optString("hook", ""),
                                planPath = cObj.optString("plan_path", ""),
                                primaryCmd = cObj.optString("primary_cmd", ""),
                                path = cObj.optString("path", "")
                            )
                        )
                    }
                }

                val activeWs = result.optString("active_workspace")
                if (activeWs.isNotBlank()) {
                    _currentWorkspace.value = activeWs
                }

                updateAgentBubble(agentMsgId) { bubble ->
                    bubble.copy(
                        text = reply,
                        steps = liveSteps.toList(),
                        actionCards = actionCards,
                        isRunning = false,
                        currentProgress = 100
                    )
                }

            } catch (e: Exception) {
                updateAgentBubble(agentMsgId) { bubble ->
                    bubble.copy(
                        text = "[ERR] Terjadi kesalahan: ${e.message}",
                        isRunning = false
                    )
                }
            } finally {
                _isBusy.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun updateAgentBubble(id: String, update: (MessageBubble) -> MessageBubble) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = update(list[idx])
            _messages.value = list
        }
    }

    private fun createNotification(title: String, content: String, progress: Int): Notification {
        val builder = NotificationCompat.Builder(this, ContentStudioApp.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (progress in 1..99) {
            builder.setProgress(100, progress, false)
        } else if (progress >= 100) {
            builder.setProgress(0, 0, false)
        } else {
            builder.setProgress(100, 0, true)
        }
        return builder.build()
    }

    private fun updateNotificationProgress(stage: String, pct: Int, msg: String) {
        val notification = createNotification("ContentStudio - $stage", msg, pct)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
