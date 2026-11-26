package com.contentstudio.app.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ChatController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val sendLock = Any()

    private val _messages = MutableStateFlow<List<ChatMessageItem>>(emptyList())
    val messages: StateFlow<List<ChatMessageItem>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSessionSummary>>(emptyList())
    val sessions: StateFlow<List<ChatSessionSummary>> = _sessions.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _liveStatus = MutableStateFlow("")
    val liveStatus: StateFlow<String> = _liveStatus.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    fun refreshSessions() {
        scope.launch {
            try {
                val res = LinuxRuntimeManager.listSessions()
                if (res.optString("status") == "ok") {
                    val arr = res.optJSONArray("sessions")
                    val list = mutableListOf<ChatSessionSummary>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val count = item.optInt("message_count", 0)
                            if (count > 0) {
                                list.add(
                                    ChatSessionSummary(
                                        id = item.optString("id", ""),
                                        title = item.optString("title", "Sesi Baru"),
                                        updatedAt = item.optLong("updated_at", 0L),
                                        messageCount = count,
                                    )
                                )
                            }
                        }
                    }
                    _sessions.value = list
                    val currentActive = _activeSessionId.value
                    if (currentActive.isBlank()) {
                        val activeFromRes = res.optString("active_session_id", "")
                        if (activeFromRes.isNotBlank()) {
                            loadSession(activeFromRes)
                        } else if (list.isNotEmpty()) {
                            loadSession(list.first().id)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadSession(id: String) {
        if (id.isBlank()) return
        scope.launch {
            try {
                val res = LinuxRuntimeManager.getSession(id)
                if (res.optString("status") == "ok") {
                    val sessionObj = res.optJSONObject("session")
                    if (sessionObj != null) {
                        val sid = sessionObj.optString("id", id)
                        _activeSessionId.value = sid
                        val msgsArr = sessionObj.optJSONArray("messages")
                        val list = mutableListOf<ChatMessageItem>()
                        if (msgsArr != null) {
                            for (i in 0 until msgsArr.length()) {
                                val m = msgsArr.getJSONObject(i)
                                val role = m.optString("role", "user")
                                val content = m.optString("content", "")
                                list.add(
                                    ChatMessageItem(
                                        id = "msg_${i}_${System.currentTimeMillis()}",
                                        isUser = role == "user",
                                        text = content,
                                        isStreaming = false,
                                    )
                                )
                            }
                        }
                        _messages.value = list
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun startNewSession(workspace: String = "") {
        scope.launch {
            try {
                val res = LinuxRuntimeManager.createSession("Sesi Baru")
                if (res.optString("status") == "ok") {
                    val sid = res.optJSONObject("session")?.optString("id", "") ?: ""
                    if (sid.isNotBlank()) {
                        _activeSessionId.value = sid
                        _messages.value = emptyList()
                        _errorMessage.value = null
                        _liveStatus.value = ""
                        refreshSessions()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun deleteSession(id: String) {
        if (id.isBlank()) return
        scope.launch {
            try {
                LinuxRuntimeManager.deleteSession(id)
                if (_activeSessionId.value == id) {
                    _activeSessionId.value = ""
                    _messages.value = emptyList()
                    val currentSessions = _sessions.value.filter { it.id != id }
                    _sessions.value = currentSessions
                    if (currentSessions.isNotEmpty()) {
                        loadSession(currentSessions.first().id)
                    } else {
                        startNewSession()
                    }
                } else {
                    refreshSessions()
                }
            } catch (_: Exception) {}
        }
    }

    fun stopStreaming() {
        activeJob?.cancel()
        activeJob = null
        LinuxRuntimeManager.stopChat()
        _isStreaming.value = false
        _liveStatus.value = ""
        val current = _messages.value.toMutableList()
        val lastIdx = current.indexOfLast { !it.isUser && it.isStreaming }
        if (lastIdx != -1) {
            val last = current[lastIdx]
            current[lastIdx] = last.copy(isStreaming = false)
            _messages.value = current
        }
    }

    fun sendMessage(prompt: String, workspace: String): Boolean {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return false

        synchronized(sendLock) {
            if (_isStreaming.value) {
                return false
            }
            _isStreaming.value = true
        }

        _errorMessage.value = null
        _liveStatus.value = "Menghubungkan..."

        val userItem = ChatMessageItem(
            isUser = true,
            text = cleanPrompt,
            isStreaming = false,
        )
        val assistantId = "stream_${System.currentTimeMillis()}"
        val assistantItem = ChatMessageItem(
            id = assistantId,
            isUser = false,
            text = "",
            isStreaming = true,
        )

        val updated = _messages.value.toMutableList()
        updated.add(userItem)
        updated.add(assistantItem)
        _messages.value = updated

        activeJob = scope.launch {
            val responseBuilder = StringBuilder()
            val sid = _activeSessionId.value

            LinuxRuntimeManager.sendChatStream(
                sessionId = sid,
                message = cleanPrompt,
                workspace = workspace,
                onStatus = { st ->
                    _liveStatus.value = st
                },
                onDelta = { delta ->
                    responseBuilder.append(delta)
                    val textSoFar = responseBuilder.toString()
                    val currentList = _messages.value.toMutableList()
                    val idx = currentList.indexOfLast { it.id == assistantId }
                    if (idx != -1) {
                        currentList[idx] = currentList[idx].copy(
                            text = textSoFar,
                            isStreaming = true,
                        )
                        _messages.value = currentList
                    }
                },
                onDone = { finalText ->
                    val resolved = if (finalText.isNotBlank()) finalText else responseBuilder.toString()
                    val currentList = _messages.value.toMutableList()
                    val idx = currentList.indexOfLast { it.id == assistantId }
                    if (idx != -1) {
                        currentList[idx] = currentList[idx].copy(
                            text = resolved.ifBlank { "Selesai tanpa output teks." },
                            isStreaming = false,
                        )
                        _messages.value = currentList
                    }
                    _isStreaming.value = false
                    _liveStatus.value = ""
                    refreshSessions()
                },
                onError = { err ->
                    val textSoFar = responseBuilder.toString()
                    val currentList = _messages.value.toMutableList()
                    val idx = currentList.indexOfLast { it.id == assistantId }
                    if (idx != -1) {
                        if (textSoFar.isNotBlank()) {
                            currentList[idx] = currentList[idx].copy(
                                text = "$textSoFar\n\nError: $err",
                                isStreaming = false,
                            )
                        } else {
                            currentList.removeAt(idx)
                        }
                        _messages.value = currentList
                    }
                    _errorMessage.value = err
                    _isStreaming.value = false
                    _liveStatus.value = ""
                    refreshSessions()
                }
            )
        }

        return true
    }
}
