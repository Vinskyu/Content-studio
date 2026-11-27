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

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _liveStatus = MutableStateFlow("")
    val liveStatus: StateFlow<String> = _liveStatus.asStateFlow()

    private val _thinkingStartTimeMs = MutableStateFlow<Long?>(null)
    val thinkingStartTimeMs: StateFlow<Long?> = _thinkingStartTimeMs.asStateFlow()

    private val _thinkingSteps = MutableStateFlow<List<String>>(emptyList())
    val thinkingSteps: StateFlow<List<String>> = _thinkingSteps.asStateFlow()

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<String>>(emptyList())
    val pendingAttachments: StateFlow<List<String>> = _pendingAttachments.asStateFlow()

    private val _draftPrompt = MutableStateFlow("")
    val draftPrompt: StateFlow<String> = _draftPrompt.asStateFlow()

    private var lastFailedPrompt: String? = null
    private var isInitialized = false
    private var activeConnection: java.net.HttpURLConnection? = null

    fun updateDraft(text: String) {
        _draftText.value = text
    }

    fun addAttachment(pathOrName: String) {
        val clean = pathOrName.trim()
        if (clean.isEmpty()) return
        if (!_pendingAttachments.value.contains(clean)) {
            _pendingAttachments.value = _pendingAttachments.value + clean
        }
    }

    fun removeAttachment(pathOrName: String) {
        _pendingAttachments.value = _pendingAttachments.value.filter { it != pathOrName }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
    }

    fun setDraftPrompt(prompt: String) {
        _draftPrompt.value = prompt
        _draftText.value = prompt
    }

    fun consumeDraftPrompt(): String {
        val current = _draftPrompt.value
        _draftPrompt.value = ""
        return current
    }

    fun ensureInitialized() {
        if (isInitialized) return
        isInitialized = true
        refreshSessions()
    }

    fun clearError() {
        _errorMessage.value = null
        _lastError.value = null
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
                        clearError()
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
                        clearError()
                        _liveStatus.value = ""
                        _thinkingStartTimeMs.value = null
                        _thinkingSteps.value = emptyList()
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
        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {}
        activeConnection = null
        activeJob?.cancel()
        activeJob = null
        LinuxRuntimeManager.stopChat()
        val startMs = _thinkingStartTimeMs.value
        val durationMs = if (startMs != null) (System.currentTimeMillis() - startMs).coerceAtLeast(0L) else 0L
        val recordedSteps = _thinkingSteps.value
        _isStreaming.value = false
        _liveStatus.value = ""
        _thinkingStartTimeMs.value = null
        val current = _messages.value.toMutableList()
        val lastIdx = current.indexOfLast { !it.isUser && it.isStreaming }
        if (lastIdx != -1) {
            val last = current[lastIdx]
            current[lastIdx] = last.copy(
                isStreaming = false,
                processSteps = recordedSteps,
                processDurationMs = durationMs
            )
            _messages.value = current
        }
    }

    fun undoLastUserMessage(): String? {
        if (_isStreaming.value) {
            stopStreaming()
        }
        val current = _messages.value.toMutableList()
        val lastUserIdx = current.indexOfLast { it.isUser }
        if (lastUserIdx == -1) return null
        val userText = current[lastUserIdx].text
        val trimmed = current.subList(0, lastUserIdx).toList()
        _messages.value = trimmed
        _draftText.value = userText
        clearError()
        return userText
    }

    fun editAndResend(messageIndex: Int, newText: String, projectName: String?) {
        val clean = newText.trim()
        if (clean.isEmpty()) return
        if (_isStreaming.value) {
            stopStreaming()
        }
        val current = _messages.value
        if (messageIndex in current.indices) {
            _messages.value = current.subList(0, messageIndex).toList()
        }
        sendMessage(clean, projectName ?: "")
    }

    fun regenerateLastResponse(projectName: String?) {
        if (_isStreaming.value) {
            stopStreaming()
        }
        val current = _messages.value.toMutableList()
        val lastUserIdx = current.indexOfLast { it.isUser }
        if (lastUserIdx == -1) return
        val lastUserText = current[lastUserIdx].text
        _messages.value = current.subList(0, lastUserIdx).toList()
        sendMessage(lastUserText, projectName ?: "")
    }

    fun retryLastFailed(projectName: String?) {
        val prompt = lastFailedPrompt ?: _messages.value.lastOrNull { it.isUser }?.text ?: return
        clearError()
        val current = _messages.value.toMutableList()
        val lastUserIdx = current.indexOfLast { it.isUser }
        if (lastUserIdx != -1 && lastUserIdx == current.lastIndex) {
            _messages.value = current.subList(0, lastUserIdx).toList()
        }
        sendMessage(prompt, projectName ?: "")
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

        clearError()
        lastFailedPrompt = cleanPrompt
        val startMs = System.currentTimeMillis()
        _thinkingStartTimeMs.value = startMs
        _thinkingSteps.value = emptyList()
        _liveStatus.value = "Menghubungkan engine lokal"

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
            val serverReady = LinuxRuntimeManager.ensureServerRunning()
            if (!serverReady) {
                val err = "Server lokal tidak merespons. Silakan coba lagi."
                _errorMessage.value = err
                _lastError.value = err
                _isStreaming.value = false
                _liveStatus.value = ""
                _thinkingStartTimeMs.value = null
                val currentList = _messages.value.toMutableList()
                val idx = currentList.indexOfLast { it.id == assistantId }
                if (idx != -1) {
                    currentList.removeAt(idx)
                    _messages.value = currentList
                }
                return@launch
            }

            val responseBuilder = StringBuilder()
            val sid = _activeSessionId.value

            LinuxRuntimeManager.sendChatStream(
                sessionId = sid,
                message = cleanPrompt,
                workspace = workspace,
                onConnectionCreated = { conn ->
                    activeConnection = conn
                },
                onStatus = { st ->
                    val cleanSt = st.trim()
                    if (cleanSt.isNotEmpty()) {
                        _liveStatus.value = cleanSt
                        if (_thinkingSteps.value.lastOrNull() != cleanSt) {
                            _thinkingSteps.value = _thinkingSteps.value + cleanSt
                        }
                    }
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
                    activeConnection = null
                    lastFailedPrompt = null
                    val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                    val recordedSteps = _thinkingSteps.value
                    val resolved = if (finalText.isNotBlank()) finalText else responseBuilder.toString()
                    val currentList = _messages.value.toMutableList()
                    val idx = currentList.indexOfLast { it.id == assistantId }
                    if (idx != -1) {
                        currentList[idx] = currentList[idx].copy(
                            text = resolved.ifBlank { "Selesai tanpa output teks." },
                            isStreaming = false,
                            processSteps = recordedSteps,
                            processDurationMs = durationMs
                        )
                        _messages.value = currentList
                    }
                    _isStreaming.value = false
                    _liveStatus.value = ""
                    _thinkingStartTimeMs.value = null
                    refreshSessions()
                },
                onError = { err ->
                    activeConnection = null
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
                    _lastError.value = err
                    _isStreaming.value = false
                    _liveStatus.value = ""
                    _thinkingStartTimeMs.value = null
                    refreshSessions()
                }
            )
        }

        return true
    }
}
