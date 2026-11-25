package com.contentstudio.app.runtime

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

data class ChatMessageItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false,
)

object LinuxRuntimeManager {

    private const val TAG = "LinuxRuntimeManager"
    const val BRIDGE_PORT = 18950
    const val PROXY_PORT = 18924
    const val BRIDGE_BASE_URL = "http://127.0.0.1:$BRIDGE_PORT"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var setupInProgress = false
    private var proxyProcess: Process? = null
    private var bridgeProcess: Process? = null

    private val _setupStage = MutableStateFlow(1)
    val setupStage: StateFlow<Int> = _setupStage.asStateFlow()

    private val _setupMessage = MutableStateFlow("Menyiapkan lingkungan Linux...")
    val setupMessage: StateFlow<String> = _setupMessage.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private fun appendLog(line: String) {
        val clean = line.trimEnd()
        if (clean.isEmpty()) return
        Log.d(TAG, clean)
        val current = _logs.value.toMutableList()
        current.add(clean)
        if (current.size > 120) {
            current.removeAt(0)
        }
        _logs.value = current
    }

    fun buildEnvironment(paths: BootstrapInstaller.Paths): Map<String, String> {
        val bionicCompat = "${paths.homeDir}/bionic-compat.js"
        val bionicOpt = if (File(bionicCompat).exists()) " -r $bionicCompat" else ""
        val opensslConf = "${paths.prefixDir}/etc/tls/openssl.cnf"
        val opensslOpt = if (File(opensslConf).exists()) "--openssl-config=$opensslConf " else ""

        return mapOf(
            "PREFIX" to paths.prefixDir,
            "HOME" to paths.homeDir,
            "PATH" to "${paths.prefixDir}/bin:${paths.prefixDir}/bin/applets:/system/bin",
            "LD_LIBRARY_PATH" to "${paths.prefixDir}/lib",
            "LD_PRELOAD" to "${paths.prefixDir}/lib/libtermux-exec.so",
            "TERMUX_PREFIX" to paths.prefixDir,
            "TERMUX__PREFIX" to paths.prefixDir,
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to paths.tmpDir,
            "TMP" to paths.tmpDir,
            "TEMP" to paths.tmpDir,
            "TERM" to "xterm-256color",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "APT_CONFIG" to "${paths.prefixDir}/etc/apt/apt.conf",
            "DPKG_ADMINDIR" to "${paths.prefixDir}/var/lib/dpkg",
            "SSL_CERT_FILE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            "CURL_CA_BUNDLE" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_SSL_CAINFO" to "${paths.prefixDir}/etc/tls/cert.pem",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "OPENSSL_CONF" to opensslConf,
            "NODE_OPTIONS" to "${opensslOpt}--unhandled-rejections=warn$bionicOpt".trim(),
            "NO_BROWSER" to "true",
            "CONTAINER" to "1",
        )
    }

    fun runInPrefix(
        context: Context,
        command: String,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)
        val shell = "${paths.prefixDir}/bin/sh"
        val pb = ProcessBuilder(shell, "-c", command)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            appendLog(line)
            onOutput?.invoke(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    fun startSetup(context: Context, forceRetry: Boolean = false) {
        if (setupInProgress) return
        if (_isReady.value && !forceRetry) return

        setupInProgress = true
        _setupError.value = null

        scope.launch {
            try {
                val paths = BootstrapInstaller.getPaths(context)
                val prefix = paths.prefixDir
                val home = paths.homeDir

                // TAHAP 1: Ekstrak bootstrap-aarch64.zip -> $PREFIX
                _setupStage.value = 1
                _setupMessage.value = "Menyiapkan sistem dasar Linux..."
                appendLog("[1/4] Memeriksa bootstrap Linux di $prefix")
                BootstrapInstaller.install(context) { msg ->
                    _setupMessage.value = msg
                    appendLog("[1/4] $msg")
                }

                // Salin aset JS (bionic-compat.js, proxy.js, studio-bridge.js) ke $HOME/
                copyAssetToHome(context, "bionic-compat.js", "$home/bionic-compat.js")
                copyAssetToHome(context, "proxy.js", "$home/proxy.js")
                copyAssetToHome(context, "studio-bridge.js", "$home/studio-bridge.js")

                // TAHAP 2: Pasang Node.js + Python + CA-Certificates
                _setupStage.value = 2
                val nodeExists = File(prefix, "bin/node").exists()
                val pythonExists = File(prefix, "bin/python3").exists() || File(prefix, "bin/python").exists()
                if (!nodeExists || !pythonExists) {
                    _setupMessage.value = "Mengunduh & memasang Node.js dan Python..."
                    appendLog("[2/4] Memasang nodejs-lts, python, dan ca-certificates...")
                    val termuxPrefix = "/data/data/com.termux/files/usr"
                    val installPkgsCmd = """
                        cd "$prefix/tmp" &&
                        rm -rf _stage *.deb 2>/dev/null || true &&
                        apt-get update --allow-insecure-repositories 2>&1;
                        apt-get download --allow-unauthenticated c-ares libicu libsqlite openssl ca-certificates nodejs-lts python libexpat gdbm libffi zlib readline ncurses bzip2 xz-utils 2>&1 &&
                        mkdir -p _stage &&
                        for deb in *.deb; do
                            [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" _stage/ 2>&1
                        done &&
                        if [ -d "_stage$termuxPrefix" ]; then
                            cp -a _stage$termuxPrefix/* "$prefix/" 2>&1
                        elif [ -d "_stage/usr" ]; then
                            cp -a _stage/usr/* "$prefix/" 2>&1
                        fi &&
                        rm -rf _stage *.deb 2>/dev/null || true
                    """.trimIndent()
                    val code = runInPrefix(context, installPkgsCmd)
                    if (code != 0 || !File(prefix, "bin/node").exists()) {
                        throw RuntimeException("Gagal memasang Node.js / Python (exit=$code). Periksa koneksi internet.")
                    }
                } else {
                    appendLog("[2/4] Node.js dan Python sudah terpasang.")
                }

                ensureNodeAndNpmWrappers(paths)

                // TAHAP 3: Pasang @google/gemini-cli secara global via npm
                _setupStage.value = 3
                val geminiModDir = File(prefix, "lib/node_modules/@google/gemini-cli")
                if (!geminiModDir.exists()) {
                    _setupMessage.value = "Memasang @google/gemini-cli..."
                    appendLog("[3/4] Menjalankan npm install -g @google/gemini-cli...")
                    val npmCode = runInPrefix(context, "npm install -g @google/gemini-cli --no-audit --no-fund 2>&1")
                    if (npmCode != 0 && !geminiModDir.exists()) {
                        throw RuntimeException("Gagal memasang @google/gemini-cli (exit=$npmCode)")
                    }
                } else {
                    appendLog("[3/4] @google/gemini-cli sudah terpasang.")
                }
                ensureGeminiWrapper(paths)

                // TAHAP 4: Jalankan proxy.js & studio-bridge.js di 127.0.0.1:18950
                _setupStage.value = 4
                _setupMessage.value = "Menyalakan server jembatan lokal..."
                appendLog("[4/4] Memulai proxy.js dan studio-bridge.js...")
                startBackgroundNodeServers(context, paths)

                var healthOk = false
                for (attempt in 1..20) {
                    val h = refreshHealth()
                    if (h != null && h.optString("status") == "ok") {
                        healthOk = true
                        break
                    }
                    delay(500)
                }

                if (!healthOk) {
                    throw RuntimeException("Server jembatan lokal port $BRIDGE_PORT tidak merespons.")
                }

                _isReady.value = true
                _setupMessage.value = "Lingkungan Linux & Gemini CLI siap."
                appendLog("[OK] Setup selesai. Bridge aktif di 127.0.0.1:$BRIDGE_PORT")
            } catch (e: Exception) {
                val msg = e.message ?: "Terjadi kesalahan saat menyiapkan runtime."
                appendLog("[ERR] $msg")
                _setupError.value = msg
            } finally {
                setupInProgress = false
            }
        }
    }

    private fun copyAssetToHome(context: Context, assetName: String, destPath: String) {
        val destFile = File(destPath)
        destFile.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun ensureNodeAndNpmWrappers(paths: BootstrapInstaller.Paths) {
        val prefix = paths.prefixDir
        val nodeBin = File(prefix, "bin/node")
        if (nodeBin.exists()) {
            try { Os.chmod(nodeBin.absolutePath, 0b111_000_000) } catch (_: Exception) {}
        }
        val npmCli = File(prefix, "lib/node_modules/npm/bin/npm-cli.js")
        if (npmCli.exists()) {
            val npmWrapper = File(prefix, "bin/npm")
            npmWrapper.writeText(
                """
                #!$prefix/bin/sh
                exec "$prefix/bin/node" "${npmCli.absolutePath}" "${'$'}@"
                """.trimIndent() + "\n"
            )
            try { Os.chmod(npmWrapper.absolutePath, 0b111_000_000) } catch (_: Exception) {}
        }
        listOf("python3", "python").forEach { pyName ->
            val pyFile = File(prefix, "bin/$pyName")
            if (pyFile.exists()) {
                try { Os.chmod(pyFile.absolutePath, 0b111_000_000) } catch (_: Exception) {}
            }
        }
    }

    private fun ensureGeminiWrapper(paths: BootstrapInstaller.Paths) {
        val prefix = paths.prefixDir
        val candidates = listOf(
            File(prefix, "lib/node_modules/@google/gemini-cli/dist/index.js"),
            File(prefix, "lib/node_modules/@google/gemini-cli/bundle/gemini.js"),
            File(prefix, "lib/node_modules/@google/gemini-cli/index.js"),
        )
        val entry = candidates.firstOrNull { it.exists() } ?: return
        val geminiBin = File(prefix, "bin/gemini")
        geminiBin.writeText(
            """
            #!$prefix/bin/sh
            exec "$prefix/bin/node" "${entry.absolutePath}" "${'$'}@"
            """.trimIndent() + "\n"
        )
        try { Os.chmod(geminiBin.absolutePath, 0b111_000_000) } catch (_: Exception) {}
    }

    private fun isProcessAlive(proc: Process?): Boolean {
        if (proc == null) return false
        return try {
            proc.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun startBackgroundNodeServers(context: Context, paths: BootstrapInstaller.Paths) {
        val env = buildEnvironment(paths)
        val nodeBin = "${paths.prefixDir}/bin/node"

        if (!isProcessAlive(proxyProcess)) {
            val pbProxy = ProcessBuilder(nodeBin, "${paths.homeDir}/proxy.js")
            pbProxy.environment().clear()
            pbProxy.environment().putAll(env)
            pbProxy.directory(File(paths.homeDir))
            pbProxy.redirectErrorStream(true)
            proxyProcess = pbProxy.start()
        }

        if (!isProcessAlive(bridgeProcess)) {
            val pbBridge = ProcessBuilder(nodeBin, "${paths.homeDir}/studio-bridge.js")
            pbBridge.environment().clear()
            pbBridge.environment().putAll(env)
            pbBridge.directory(File(paths.homeDir))
            pbBridge.redirectErrorStream(true)
            val proc = pbBridge.start()
            bridgeProcess = proc
            scope.launch {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            appendLog("[bridge] $line")
                            line = reader.readLine()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ── Bridge HTTP / SSE Client ───────────────────────────────────────────────

    private fun httpRequest(method: String, path: String, jsonBody: String? = null, timeoutMs: Int = 15000): JSONObject {
        val conn = (URL("$BRIDGE_BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (jsonBody != null) {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        return JSONObject(text)
    }

    fun refreshHealth(): JSONObject? {
        return try {
            val res = httpRequest("GET", "/health", timeoutMs = 4000)
            _isLoggedIn.value = res.optBoolean("logged_in", false)
            val sid = res.optString("active_session_id", "")
            if (sid.isNotBlank()) {
                _activeSessionId.value = sid
            }
            res
        } catch (_: Exception) {
            null
        }
    }

    fun startOAuthLogin(): JSONObject {
        return try {
            httpRequest("POST", "/auth/start", "{}", timeoutMs = 25000)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal memulai OAuth login")
            }
        }
    }

    fun listSessions(): JSONObject {
        return try {
            httpRequest("GET", "/sessions")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal memuat sesi")
            }
        }
    }

    fun getSession(id: String): JSONObject {
        return try {
            val res = httpRequest("GET", "/sessions/$id")
            if (res.optString("status") == "ok") {
                _activeSessionId.value = id
            }
            res
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal memuat detail sesi")
            }
        }
    }

    fun createSession(title: String = "Sesi Baru"): JSONObject {
        return try {
            val body = JSONObject().apply { put("title", title) }.toString()
            val res = httpRequest("POST", "/sessions", body)
            val sid = res.optJSONObject("session")?.optString("id", "") ?: ""
            if (sid.isNotBlank()) {
                _activeSessionId.value = sid
            }
            res
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal membuat sesi baru")
            }
        }
    }

    fun deleteSession(id: String): JSONObject {
        return try {
            httpRequest("DELETE", "/sessions/$id")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal menghapus sesi")
            }
        }
    }

    fun stopChat() {
        scope.launch {
            try {
                httpRequest("POST", "/chat/stop", "{}")
            } catch (_: Exception) {}
        }
    }

    fun sendChatStream(
        sessionId: String,
        message: String,
        onStatus: (String) -> Unit,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            val body = JSONObject().apply {
                put("session_id", sessionId)
                put("message", message)
            }.toString()

            val conn = (URL("$BRIDGE_BASE_URL/chat").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 180000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errTxt = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                onError(errTxt)
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                var finished = false
                while (line != null) {
                    if (line.startsWith("data: ")) {
                        val jsonStr = line.removePrefix("data: ").trim()
                        if (jsonStr.isNotEmpty()) {
                            try {
                                val obj = JSONObject(jsonStr)
                                val type = obj.optString("type")
                                val text = obj.optString("text", "")
                                when (type) {
                                    "status" -> onStatus(text)
                                    "delta" -> onDelta(text)
                                    "done" -> {
                                        finished = true
                                        onDone(text)
                                    }
                                    "error" -> {
                                        finished = true
                                        onError(text)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    line = reader.readLine()
                }
                if (!finished) {
                    onDone("")
                }
            }
        } catch (e: Exception) {
            onError(e.message ?: "Koneksi ke bridge terputus")
        }
    }
}
