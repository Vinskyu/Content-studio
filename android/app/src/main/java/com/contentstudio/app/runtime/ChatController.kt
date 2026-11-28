package com.contentstudio.app.runtime

import com.contentstudio.app.ui.components.ThinkingEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ChatController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val sendLock = Any()

    /**
     * Sanitasi defensif terakhir sebelum teks agent disimpan/ditampilkan:
     * buang baris [SUGGESTIONS] / [ACTION_PLAN] beserta pecahan JSON yang bocor
     * akibat marker terpotong di tengah chunk jaringan.
     */
    private fun sanitizeAgentText(raw: String): String {
        if (raw.isBlank()) return raw
        var text = raw
        for (marker in listOf("[SUGGESTIONS]", "[ACTION_PLAN]")) {
            text = text.split('\n')
                .filterNot { it.trim().startsWith(marker) }
                .joinToString("\n")
        }
        val remnant = Regex("^[^\"\\[\\]{}]{0,80}\"[^\"\\n]*\"(\\s*,\\s*\"[^\"\\n]*\")*\\s*\\]\\s*$")
        text = text.split('\n')
            .filterNot { remnant.matches(it.trim()) }
            .joinToString("\n")
        return text.trim()
    }

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

    private val _thinkingEntries = MutableStateFlow<List<ThinkingEntry>>(emptyList())
    val thinkingEntries: StateFlow<List<ThinkingEntry>> = _thinkingEntries.asStateFlow()

    private val _chatMode = MutableStateFlow("director")
    val chatMode: StateFlow<String> = _chatMode.asStateFlow()

    private val _hubProvider = MutableStateFlow("auto")
    val hubProvider: StateFlow<String> = _hubProvider.asStateFlow()

    private val _hubModel = MutableStateFlow("smart-auto")
    val hubModel: StateFlow<String> = _hubModel.asStateFlow()

    private val _hubEffort = MutableStateFlow("medium")
    val hubEffort: StateFlow<String> = _hubEffort.asStateFlow()

    private val _hubQuotaStatus = MutableStateFlow("ok")
    val hubQuotaStatus: StateFlow<String> = _hubQuotaStatus.asStateFlow()

    fun setHubQuotaStatus(status: String) {
        _hubQuotaStatus.value = status.ifBlank { "ok" }
    }

    fun setHubSelection(provider: String, model: String = "smart-auto", effort: String = "medium") {
        _hubProvider.value = provider.ifBlank { "auto" }.lowercase()
        _hubModel.value = model.ifBlank { "smart-auto" }
        _hubEffort.value = effort.ifBlank { "medium" }.lowercase()
    }

    fun applyHubSnapshot(provider: String, model: String, effort: String) {
        _hubProvider.value = provider.ifBlank { "auto" }
        _hubModel.value = model.ifBlank { "smart-auto" }
        _hubEffort.value = effort.ifBlank { "medium" }
    }

    private val _uiSyncEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val uiSyncEvents: SharedFlow<Pair<String, String>> = _uiSyncEvents.asSharedFlow()

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<String>>(emptyList())
    val pendingAttachments: StateFlow<List<String>> = _pendingAttachments.asStateFlow()

    private val _draftPrompt = MutableStateFlow("")
    val draftPrompt: StateFlow<String> = _draftPrompt.asStateFlow()

    private val _mediaConfig = MutableStateFlow<StockMediaConfig?>(null)
    val mediaConfig: StateFlow<StockMediaConfig?> = _mediaConfig.asStateFlow()

    private var lastFailedPrompt: String? = null
    private var isInitialized = false
    private var activeConnection: java.net.HttpURLConnection? = null

    fun setChatMode(mode: String) {
        val clean = mode.trim().lowercase()
        if (clean in listOf("director", "scriptwriter", "researcher", "fast")) {
            _chatMode.value = clean
        }
    }

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

    private val _projectMemories = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val projectMemories: StateFlow<List<MemoryEntry>> = _projectMemories.asStateFlow()

    private val _globalMemories = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val globalMemories: StateFlow<List<MemoryEntry>> = _globalMemories.asStateFlow()

    fun refreshMemories(projectName: String?) {
        scope.launch {
            val (pList, gList) = LinuxRuntimeManager.getMemories(projectName)
            _projectMemories.value = pList
            _globalMemories.value = gList
        }
    }

    fun addMemory(projectName: String?, note: String, category: String = "decision") {
        scope.launch {
            val ok = LinuxRuntimeManager.addMemory(projectName, note, category)
            if (ok) refreshMemories(projectName)
        }
    }

    fun deleteMemory(projectName: String?, id: String) {
        scope.launch {
            val ok = LinuxRuntimeManager.deleteMemory(projectName, id)
            if (ok) refreshMemories(projectName)
        }
    }

    fun approveActionPlan(messageIndex: Int, projectName: String?) {
        val currentList = _messages.value.toMutableList()
        if (messageIndex in currentList.indices) {
            val item = currentList[messageIndex]
            val plan = item.actionPlan
            if (plan != null && plan.status == "pending") {
                currentList[messageIndex] = item.copy(actionPlan = plan.copy(status = "approved"))
                _messages.value = currentList
                sendMessage(plan.approvePrompt, projectName.orEmpty())
            }
        }
    }

    fun dismissActionPlan(messageIndex: Int) {
        val currentList = _messages.value.toMutableList()
        if (messageIndex in currentList.indices) {
            val item = currentList[messageIndex]
            val plan = item.actionPlan
            if (plan != null && plan.status == "pending") {
                currentList[messageIndex] = item.copy(actionPlan = plan.copy(status = "dismissed"))
                _messages.value = currentList
            }
        }
    }

    fun togglePinMessage(messageIndex: Int) {
        val currentList = _messages.value.toMutableList()
        if (messageIndex in currentList.indices) {
            val item = currentList[messageIndex]
            currentList[messageIndex] = item.copy(isPinned = !item.isPinned)
            _messages.value = currentList
        }
    }

    fun refreshMediaConfig() {
        scope.launch {
            val cfg = LinuxRuntimeManager.getMediaConfig()
            _mediaConfig.value = cfg
        }
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
        scope.launch {
            LinuxRuntimeManager.getHubSettings()?.let { snap ->
                applyHubSnapshot(snap.activeProvider, snap.activeModel, snap.effort)
                val quota = if (snap.activeProvider == "auto") {
                    snap.quota.values.firstOrNull { it.status == "exhausted" }
                        ?: snap.quota.values.firstOrNull { it.status == "warning" }
                        ?: snap.quota.values.firstOrNull()
                } else {
                    snap.quota[snap.activeProvider]
                }
                setHubQuotaStatus(quota?.status ?: "ok")
            }
        }
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
                            val item = arr.optJSONObject(i) ?: continue
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
                                val m = msgsArr.optJSONObject(i) ?: continue
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
                        _thinkingEntries.value = emptyList()
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

    fun renameSession(id: String, newTitle: String) {
        if (id.isBlank() || newTitle.isBlank()) return
        scope.launch {
            try {
                LinuxRuntimeManager.renameSession(id, newTitle)
                refreshSessions()
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
        _thinkingEntries.value = emptyList()
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

    fun dispatchSlashCommand(command: String, workspace: String): Boolean {
        val parts = command.trim().removePrefix("/").split(" ", limit = 2)
        val cmd = parts.getOrElse(0) { "" }.lowercase()
        val arg = parts.getOrElse(1) { "" }.trim()
        return when (cmd) {
            "clear" -> {
                _messages.value = emptyList()
                clearError()
                true
            }
            "media" -> {
                val prompt = if (arg.isNotBlank()) "Cari media stock: $arg" else "Tampilkan status media engine"
                sendMessage(prompt, workspace)
            }
            "dl" -> {
                if (arg.isBlank()) false
                else sendMessage("Unduh media dari URL: $arg", workspace)
            }
            "script" -> sendMessage("Tulis naskah narasi untuk proyek $workspace", workspace)
            "scenes" -> sendMessage("Pecah naskah menjadi daftar adegan untuk proyek $workspace", workspace)
            "hook" -> sendMessage("Buat hook pembuka yang kuat untuk proyek $workspace", workspace)
            "publish" -> sendMessage("Buat publish kit (judul, deskripsi, hashtag) untuk proyek $workspace", workspace)
            else -> sendMessage(command, workspace)
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

        clearError()
        lastFailedPrompt = cleanPrompt
        val currentAttachments = _pendingAttachments.value
        _pendingAttachments.value = emptyList()
        val activeMode = _chatMode.value
        val activeProvider = _hubProvider.value
        val activeModel = _hubModel.value
        val activeEffort = _hubEffort.value
        val startMs = System.currentTimeMillis()
        _thinkingStartTimeMs.value = startMs
        _thinkingSteps.value = emptyList()
        _thinkingEntries.value = emptyList()
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
                _thinkingEntries.value = emptyList()
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
            var receivedActionPlan: ActionPlanCardData? = null
            var receivedSuggestions: List<String> = emptyList()
            var receivedMeta: HubMeta? = null

            LinuxRuntimeManager.sendChatStream(
                sessionId = sid,
                message = cleanPrompt,
                workspace = workspace,
                mode = activeMode,
                provider = activeProvider,
                model = activeModel,
                effort = activeEffort,
                attachments = currentAttachments,
                onMeta = { meta ->
                    receivedMeta = meta
                    val currentList = _messages.value.toMutableList()
                    val idx = currentList.indexOfLast { it.id == assistantId }
                    if (idx != -1) {
                        currentList[idx] = currentList[idx].copy(hubMeta = meta)
                        _messages.value = currentList
                    }
                },
                onConnectionCreated = { conn ->
                    activeConnection = conn
                },
                onStatus = { st ->
                    val cleanSt = st.trim()
                    if (cleanSt.isNotEmpty()) {
                        _liveStatus.value = cleanSt
                        val steps = _thinkingSteps.value
                        if (steps.lastOrNull() != cleanSt) {
                            // Simpan maksimal 30 baris log terakhir agar kartu tetap ringan.
                            _thinkingSteps.value = (steps + cleanSt).takeLast(30)
                        }
                    }
                },
                onThinking = { kind, thoughtText ->
                    val cleanThought = thoughtText.trim()
                    if (cleanThought.isNotEmpty()) {
                        val entries = _thinkingEntries.value
                        val last = entries.lastOrNull()
                        if (last == null || last.kind != kind || last.text != cleanThought) {
                            val entry = ThinkingEntry(
                                kind = if (kind == "thought") "thought" else "step",
                                text = cleanThought,
                                timestampMs = System.currentTimeMillis()
                            )
                            _thinkingEntries.value = (entries + entry).takeLast(60)
                        }
                    }
                },
                onUiSync = { scope, target ->
                    _uiSyncEvents.tryEmit(Pair(scope, target))
                },
                onActionPlan = { plan ->
                    receivedActionPlan = plan
                },
                onSuggestions = { sugs ->
                    receivedSuggestions = sugs
                },
                onDelta = { delta ->
                    responseBuilder.append(delta)
                    val textSoFar = sanitizeAgentText(responseBuilder.toString())
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
                    val resolved = sanitizeAgentText(
                        if (finalText.isNotBlank()) finalText else responseBuilder.toString()
                    )
                    val effectiveSuggestions = if (receivedSuggestions.isNotEmpty()) {
                        receivedSuggestions
                    } else if (workspace.isNullOrBlank()) {
                        listOf("Buat Project Baru", "Riset Ide Niche")
                    } else {
                        listOf("Pecah ke Scenes", "Cari Stok Media", "Generate Voiceover")
                    }
                    val currentList = _messages.value.toMutableList()
                    val idx = currentList.indexOfLast { it.id == assistantId }
                    if (idx != -1) {
                        currentList[idx] = currentList[idx].copy(
                            text = resolved.ifBlank { "Selesai tanpa output teks." },
                            isStreaming = false,
                            processSteps = recordedSteps,
                            processDurationMs = durationMs,
                            actionPlan = receivedActionPlan,
                            suggestions = effectiveSuggestions,
                            hubMeta = receivedMeta
                        )
                        _messages.value = currentList
                    }
                    _isStreaming.value = false
                    _liveStatus.value = ""
                    _thinkingStartTimeMs.value = null
                    _thinkingEntries.value = emptyList()
                    refreshSessions()
                },
                onError = { err ->
                    activeConnection = null
                    val textSoFar = sanitizeAgentText(responseBuilder.toString())
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
                    _thinkingEntries.value = emptyList()
                    refreshSessions()
                }
            )
        }

        return true
    }
}
