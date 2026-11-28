package com.contentstudio.app.runtime

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

data class VoiceProfile(
    val voice: String = "en-US-BrianMultilingualNeural",
    val rate: String = "+0%",
    val pitch: String = "+0Hz",
    val masterPreset: String = "natural",
)

data class ChannelDna(
    val learnedTraits: List<String> = emptyList(),
    val referenceNotes: String = "",
    val avgSceneDuration: Float = 3.5f,
    val pacingWpm: Int = 145,
    val preferredVoice: String = "id-ID-ArdiNeural",
    val visualRules: String = "",
    val subtitleStyle: String = "bottom_bold",
    val styleSummary: String = "",
    val voiceProfile: VoiceProfile = VoiceProfile(),
)

data class ChannelProfile(
    val id: String,
    val name: String,
    val niche: String = "",
    val targetAudience: String = "",
    val aspectRatio: String = "9:16",
    val tone: String = "",
    val visualRules: String = "",
    val subtitleStyle: String = "",
    val styleNotes: String = "",
    val createdAt: Long = 0L,
    val dna: ChannelDna = ChannelDna(),
)

data class PatternBeat(
    val role: String,
    val duration: Float,
)

data class PatternItem(
    val id: String,
    val name: String,
    val aspectRatio: String = "9:16",
    val beatsCount: Int = 0,
    val totalDuration: Float = 0f,
    val beats: List<PatternBeat> = emptyList(),
)

data class WorkspaceSummary(
    val name: String,
    val path: String,
    val filesCount: Int,
    val updatedAt: Long,
    val channelId: String = "",
    val aspectRatio: String = "9:16",
    val stage: String = "script",
)

data class LibraryAssetItem(
    val name: String,
    val category: String,
    val size: Long,
    val mtime: Long,
    val path: String,
    val relPath: String,
)

data class WorkspaceFileItem(
    val name: String,
    val relPath: String,
    val size: Long,
    val category: String,
    val updatedAt: Long,
)

data class MemoryEntry(
    val id: String,
    val note: String,
    val category: String,
    val createdAt: String
)

data class StudioMediaFile(
    val id: String,
    val name: String,
    val type: String,
    val category: String,
    val project: String,
    val relativePath: String,
    val sizeBytes: Long,
    val updatedAt: Long,
    val streamUrl: String,
    val thumbUrl: String = "",
)

data class ActionPlanCardData(
    val title: String,
    val goal: String = "",
    val steps: List<String>,
    val approvePrompt: String,
    val status: String = "pending" // pending | approved | dismissed
)

data class ChatMessageItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false,
    val processSteps: List<String> = emptyList(),
    val processDurationMs: Long = 0L,
    val mediaItems: List<StockMediaItem> = emptyList(),
    val actionPlan: ActionPlanCardData? = null,
    val suggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
)

data class ExtensionSkillItem(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val enabled: Boolean = true,
)

data class ExtensionMcpItem(
    val id: String,
    val name: String,
    val type: String,
    val target: String,
    val status: String,
)

data class StockMediaConfig(
    val pexelsConfigured: Boolean,
    val pixabayConfigured: Boolean,
    val ytdlpReady: Boolean,
    val active: Boolean
)

data class StockMediaItem(
    val id: String,
    val provider: String,
    val type: String,
    val title: String,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val width: Int,
    val height: Int,
    val durationSec: Int,
    val author: String
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
    private var appContext: Context? = null
    @Volatile
    private var isIntentionalShutdown = false
    private val restartTimestamps = mutableListOf<Long>()

    fun setApplicationContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

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

    private val _activeWorkspace = MutableStateFlow("")
    val activeWorkspace: StateFlow<String> = _activeWorkspace.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _uiSyncVersion = MutableStateFlow(0L)
    val uiSyncVersion: StateFlow<Long> = _uiSyncVersion.asStateFlow()

    private fun incrementUiSyncVersion() {
        _uiSyncVersion.value = _uiSyncVersion.value + 1L
    }

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
            "npm_config_prefix" to paths.prefixDir,
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

    private fun runBinary(
        context: Context,
        executable: String,
        args: List<String>,
    ): Int {
        val paths = BootstrapInstaller.getPaths(context)
        val env = buildEnvironment(paths)
        val cmdList = mutableListOf(executable)
        cmdList.addAll(args)
        val pb = ProcessBuilder(cmdList)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.directory(File(paths.homeDir))
        pb.redirectErrorStream(true)

        val proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line = reader.readLine()
        while (line != null) {
            appendLog(line)
            line = reader.readLine()
        }
        return proc.waitFor()
    }

    private fun verifyNodeExec(context: Context): Boolean {
        val paths = BootstrapInstaller.getPaths(context)
        val nodeFile = File(paths.prefixDir, "bin/node")
        if (!nodeFile.exists()) {
            appendLog("[2/4] Binary node belum ditemukan di ${nodeFile.absolutePath}")
            return false
        }
        return try {
            Os.chmod(nodeFile.absolutePath, 0b111_000_000)
            val exitCode = runBinary(context, nodeFile.absolutePath, listOf("-v"))
            if (exitCode != 0) {
                appendLog("[2/4] Eksekusi node -v gagal dengan exit code $exitCode")
            }
            exitCode == 0
        } catch (e: Exception) {
            appendLog("[2/4] Gagal menjalankan node -v: ${e.message}")
            false
        }
    }

    private fun isValidJsScript(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return try {
            val head = file.bufferedReader().use { reader ->
                buildString {
                    for (i in 0 until 3) {
                        val line = reader.readLine() ?: break
                        appendLine(line)
                    }
                }
            }
            // Tolak jika file JS pernah tertimpa wrapper shell sh/exec
            !head.contains("/bin/sh") && !head.contains("exec \"")
        } catch (_: Exception) {
            false
        }
    }

    private fun patchShebangs(binDir: File, prefixDir: String) {
        if (!binDir.isDirectory) return
        val termuxBin = "/data/data/com.termux/files/usr/bin/"
        val newBin = "$prefixDir/bin/"
        binDir.listFiles()?.forEach { file ->
            try {
                // Jangan pernah memodifikasi target file lewat symlink di bin/
                val st = Os.lstat(file.absolutePath)
                if (OsConstants.S_ISLNK(st.st_mode)) {
                    return@forEach
                }
                if (!file.isFile) return@forEach

                val bytes = file.readBytes()
                if (bytes.size >= 4 &&
                    bytes[0] == 0x7f.toByte() &&
                    bytes[1] == 'E'.code.toByte() &&
                    bytes[2] == 'L'.code.toByte() &&
                    bytes[3] == 'F'.code.toByte()
                ) {
                    return@forEach
                }
                if (bytes.size >= 2 && bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()) {
                    val content = String(bytes, Charsets.UTF_8)
                    val firstNewline = content.indexOf('\n')
                    val firstLine = if (firstNewline >= 0) content.substring(0, firstNewline) else content
                    val rest = if (firstNewline >= 0) content.substring(firstNewline) else ""
                    var patchedLine = firstLine
                    if (patchedLine.contains(termuxBin)) {
                        patchedLine = patchedLine.replace(termuxBin, newBin)
                    }
                    if (patchedLine.startsWith("#!/usr/bin/env ")) {
                        patchedLine = patchedLine.replaceFirst("#!/usr/bin/env ", "#!$prefixDir/bin/env ")
                    } else if (patchedLine.startsWith("#!/usr/bin/")) {
                        patchedLine = patchedLine.replaceFirst("#!/usr/bin/", "#!$prefixDir/bin/")
                    }
                    if (patchedLine != firstLine) {
                        file.writeText(patchedLine + rest, Charsets.UTF_8)
                    }
                    Os.chmod(file.absolutePath, 0b111_000_000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal patch shebang ${file.name}: ${e.message}")
            }
        }
    }

    fun startSetup(context: Context, forceRetry: Boolean = false) {
        setApplicationContext(context)
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

                // Salin aset JS dan helper PTY (bionic-compat.js, proxy.js, studio-bridge.js, pty-bridge.py) ke $HOME/
                copyAssetToHome(context, "bionic-compat.js", "$home/bionic-compat.js")
                copyAssetToHome(context, "proxy.js", "$home/proxy.js")
                copyAssetToHome(context, "studio-bridge.js", "$home/studio-bridge.js")
                try {
                    copyAssetToHome(context, "pty-bridge.py", "$home/pty-bridge.py")
                    File("$home/pty-bridge.py").setExecutable(true)
                } catch (_: Exception) {}

                // Ekstrak dapur produksi ContentStudio (AGENTS.md, .agents/skills/, engine/) ke $HOME/ContentStudio/
                extractStudioCore(context, "$home/ContentStudio")
                File("$home/ContentStudio/workspaces").mkdirs()

                // TAHAP 2: Pasang & verifikasi libc++ terbaru + Node.js + npm-cli.js + Python + CA-Certificates
                _setupStage.value = 2
                val npmCliFile = File(prefix, "lib/node_modules/npm/bin/npm-cli.js")
                val pythonExists = File(prefix, "bin/python3").exists() || File(prefix, "bin/python").exists()
                val nodeExecOk = verifyNodeExec(context)
                val npmValid = isValidJsScript(npmCliFile)

                if (!nodeExecOk || !npmValid || !pythonExists) {
                    _setupMessage.value = "Mengunduh & memperbarui libc++, Node.js, dan Python..."
                    appendLog("[2/4] Verifikasi awal: nodeOk=$nodeExecOk, npmValid=$npmValid, python=$pythonExists. Memasang paket...")
                    val termuxPrefix = "/data/data/com.termux/files/usr"
                    val installPkgsCmd = """
                        cd "$prefix/tmp" &&
                        rm -rf _stage *.deb "$prefix/var/cache/apt/archives/"*.deb 2>/dev/null || true &&
                        rm -f "$prefix/bin/npm" "$prefix/bin/npx" 2>/dev/null || true &&
                        apt-get update --allow-insecure-repositories 2>&1;
                        apt-get install -y --no-install-recommends --download-only --reinstall --allow-unauthenticated libc++ nodejs-lts npm python ca-certificates 2>&1 || true;
                        apt-get download --allow-unauthenticated libc++ c-ares libicu libsqlite openssl ca-certificates nodejs-lts npm python libexpat gdbm libffi zlib readline ncurses bzip2 xz-utils 2>&1 || true;
                        mkdir -p _stage &&
                        for deb in "$prefix/var/cache/apt/archives/"*.deb "$prefix/tmp/"*.deb; do
                            [ -f "${'$'}deb" ] && echo "Mengekstrak ${'$'}(basename "${'$'}deb")..." && dpkg-deb -x "${'$'}deb" _stage/ 2>&1
                        done &&
                        if [ -d "_stage$termuxPrefix" ]; then
                            if [ -f "_stage$termuxPrefix/lib/libc++_shared.so" ]; then
                                rm -f "$prefix/lib/libc++_shared.so" 2>/dev/null || true
                            fi &&
                            rm -f "$prefix/bin/npm" "$prefix/bin/npx" 2>/dev/null || true &&
                            cp -af _stage$termuxPrefix/* "$prefix/" 2>&1
                        elif [ -d "_stage/usr" ]; then
                            if [ -f "_stage/usr/lib/libc++_shared.so" ]; then
                                rm -f "$prefix/lib/libc++_shared.so" 2>/dev/null || true
                            fi &&
                            rm -f "$prefix/bin/npm" "$prefix/bin/npx" 2>/dev/null || true &&
                            cp -af _stage/usr/* "$prefix/" 2>&1
                        fi &&
                        chmod 755 "$prefix/lib/libc++_shared.so" 2>/dev/null || true;
                        rm -rf _stage *.deb "$prefix/var/cache/apt/archives/"*.deb 2>/dev/null || true
                    """.trimIndent()
                    val code = runInPrefix(context, installPkgsCmd)
                    patchShebangs(File(prefix, "bin"), prefix)
                    if (code != 0) {
                        appendLog("[2/4] Perintah apt/extract selesai dengan exit=$code")
                    }
                    if (!verifyNodeExec(context)) {
                        throw RuntimeException("Gagal menjalankan binary node (cek detail log di bawah)")
                    }
                    if (!isValidJsScript(npmCliFile)) {
                        throw RuntimeException("File npm-cli.js tidak valid atau hilang setelah ekstraksi (cek detail log di bawah)")
                    }
                } else {
                    appendLog("[2/4] Node.js, npm-cli.js, dan Python terverifikasi.")
                }

                ensureNodeAndNpmWrappers(paths)
                patchShebangs(File(prefix, "bin"), prefix)

                // Pastikan binary ffmpeg tersedia untuk engine rendering video & VO
                val ffmpegBin = File(prefix, "bin/ffmpeg")
                if (!ffmpegBin.exists()) {
                    appendLog("[2/4] Memeriksa ffmpeg: belum terpasang. Mengunduh ffmpeg...")
                    try {
                        val installFfmpegCmd = """
                            cd "$prefix/tmp" &&
                            apt-get update --allow-insecure-repositories 2>&1 || true;
                            apt-get install -y --no-install-recommends ffmpeg 2>&1 || apt-get download ffmpeg 2>&1 || true;
                            for deb in "$prefix/var/cache/apt/archives/"*ffmpeg*.deb "$prefix/tmp/"*ffmpeg*.deb; do
                                [ -f "${'$'}deb" ] && dpkg-deb -x "${'$'}deb" "$prefix/" 2>&1 || true
                            done;
                            chmod 755 "$prefix/bin/ffmpeg" 2>/dev/null || true;
                            rm -f "$prefix/tmp/"*.deb 2>/dev/null || true
                        """.trimIndent()
                        runInPrefix(context, installFfmpegCmd)
                        if (ffmpegBin.exists()) {
                            appendLog("[2/4] ffmpeg berhasil dipasang di $prefix/bin/ffmpeg")
                        } else {
                            appendLog("[2/4] Peringatan: ffmpeg belum terpasang, proses setup tetap dilanjutkan.")
                        }
                    } catch (e: Exception) {
                        appendLog("[2/4] Peringatan saat memasang ffmpeg: ${e.message}")
                    }
                } else {
                    appendLog("[2/4] ffmpeg ($prefix/bin/ffmpeg) terverifikasi dan siap.")
                }

                // Pastikan edge-tts terpasang untuk fitur TTS AI
                appendLog("[2/4] Memeriksa modul edge-tts...")
                try {
                    val installEdgeTtsCmd = """
                        python3 -m pip --version >/dev/null 2>&1 || python3 -m ensurepip >/dev/null 2>&1 || true;
                        python3 -c "import edge_tts" >/dev/null 2>&1 || python3 -m pip install --no-cache-dir --break-system-packages edge-tts 2>&1 || python3 -m pip install --no-cache-dir edge-tts 2>&1 || true
                    """.trimIndent()
                    runInPrefix(context, installEdgeTtsCmd)
                    appendLog("[2/4] edge-tts terverifikasi.")
                } catch (e: Exception) {
                    appendLog("[2/4] Catatan instalasi edge-tts: ${e.message}")
                }

                // TAHAP 3: Unduh Antigravity CLI (ARM64)
                _setupStage.value = 3
                val agyBin = File(prefix, "bin/antigravity")
                var agyOk = false
                if (agyBin.exists()) {
                    val code = runBinary(context, agyBin.absolutePath, listOf("--help"))
                    if (code == 0) {
                        agyOk = true
                    }
                }

                if (!agyOk) {
                    _setupMessage.value = "Mengunduh Antigravity CLI (ARM64)..."
                    appendLog("[3/4] Mengunduh Antigravity CLI (ARM64)...")

                    val manifestUrl = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/android_arm64.json"
                    val fallbackUrl = "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.5.17-4823843970351104/android-arm/cli_android_arm64.tar.gz"

                    var downloadUrl = fallbackUrl
                    try {
                        val conn = URL(manifestUrl).openConnection() as HttpURLConnection
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        if (conn.responseCode == 200) {
                            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                            val manifest = JSONObject(jsonText)
                            val parsedUrl = manifest.optString("url", "")
                            if (parsedUrl.isNotBlank()) {
                                downloadUrl = parsedUrl
                            }
                        }
                    } catch (e: Exception) {
                        appendLog("[3/4] Gagal membaca manifest: ${e.message}, menggunakan fallback URL.")
                    }

                    appendLog("[3/4] Mengunduh paket dari $downloadUrl...")
                    val dlScript = """
                        TMP_DIR="$prefix/tmp/agy_install" &&
                        mkdir -p "${'$'}TMP_DIR" &&
                        rm -f "${'$'}TMP_DIR/cli_android_arm64.tar.gz" &&
                        curl -fsSL --retry 3 "$downloadUrl" -o "${'$'}TMP_DIR/cli_android_arm64.tar.gz" 2>&1 &&
                        tar -xzf "${'$'}TMP_DIR/cli_android_arm64.tar.gz" -C "$prefix/bin" 2>&1 &&
                        chmod 755 "$prefix/bin/antigravity" 2>&1 &&
                        rm -rf "${'$'}TMP_DIR"
                    """.trimIndent()
                    val dlCode = runInPrefix(context, dlScript)
                    if (dlCode != 0 || !agyBin.exists()) {
                        throw RuntimeException("Gagal mengunduh atau mengekstrak Antigravity CLI (exit=$dlCode)")
                    }
                    val verifyCode = runBinary(context, agyBin.absolutePath, listOf("--help"))
                    if (verifyCode != 0) {
                        throw RuntimeException("Verifikasi binary Antigravity CLI gagal (exit=$verifyCode)")
                    }
                    appendLog("[3/4] Antigravity CLI berhasil dipasang dan diverifikasi.")
                } else {
                    appendLog("[3/4] Antigravity CLI ($prefix/bin/antigravity) sudah terpasang dan siap.")
                }

                patchShebangs(File(prefix, "bin"), prefix)

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
                _setupMessage.value = "Lingkungan Linux & Antigravity CLI siap."
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

    private fun extractStudioCore(context: Context, targetDir: String) {
        val dest = File(targetDir)
        dest.mkdirs()
        File(dest, "workspaces").mkdirs()

        try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (assetList.contains("studio-core.zip")) {
                context.assets.open("studio-core.zip").use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(dest, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { output ->
                                    zis.copyTo(output)
                                }
                                if (entry.name.endsWith(".py") || entry.name.endsWith(".sh")) {
                                    outFile.setExecutable(true)
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                appendLog("[1/4] Dapur produksi studio-core berhasil disiapkan di $targetDir")
            }
        } catch (e: Exception) {
            appendLog("[1/4] Peringatan ekstrak studio-core.zip: ${e.message}")
        }
    }

    private fun ensureNodeAndNpmWrappers(paths: BootstrapInstaller.Paths) {
        val prefix = paths.prefixDir
        val nodeBin = File(prefix, "bin/node")
        if (nodeBin.exists()) {
            try { Os.chmod(nodeBin.absolutePath, 0b111_000_000) } catch (_: Exception) {}
        }
        val npmCli = File(prefix, "lib/node_modules/npm/bin/npm-cli.js")
        if (isValidJsScript(npmCli)) {
            val npmWrapper = File(prefix, "bin/npm")
            // Wajib unlink/delete symlink terlebih dahulu agar writeText tidak menimpa npm-cli.js!
            try { npmWrapper.delete() } catch (_: Exception) {}
            npmWrapper.writeText(
                """
                #!$prefix/bin/sh
                exec "$prefix/bin/node" "${npmCli.absolutePath}" "${'$'}@"
                """.trimIndent() + "\n"
            )
            try { Os.chmod(npmWrapper.absolutePath, 0b111_000_000) } catch (_: Exception) {}
        }
        val npxCli = File(prefix, "lib/node_modules/npm/bin/npx-cli.js")
        if (isValidJsScript(npxCli)) {
            val npxWrapper = File(prefix, "bin/npx")
            try { npxWrapper.delete() } catch (_: Exception) {}
            npxWrapper.writeText(
                """
                #!$prefix/bin/sh
                exec "$prefix/bin/node" "${npxCli.absolutePath}" "${'$'}@"
                """.trimIndent() + "\n"
            )
            try { Os.chmod(npxWrapper.absolutePath, 0b111_000_000) } catch (_: Exception) {}
        }
        listOf("python3", "python").forEach { pyName ->
            val pyFile = File(prefix, "bin/$pyName")
            if (pyFile.exists()) {
                try { Os.chmod(pyFile.absolutePath, 0b111_000_000) } catch (_: Exception) {}
            }
        }
    }

    private fun resolveGeminiEntry(prefix: String): File? {
        val pkgDir = File(prefix, "lib/node_modules/@google/gemini-cli")
        val pkgJson = File(pkgDir, "package.json")
        if (pkgJson.exists()) {
            try {
                val obj = JSONObject(pkgJson.readText())
                val binVal = obj.opt("bin")
                val relPath = when (binVal) {
                    is String -> binVal
                    is JSONObject -> binVal.optString("gemini", "").ifBlank {
                        val keys = binVal.keys()
                        if (keys.hasNext()) binVal.optString(keys.next(), "") else ""
                    }
                    else -> ""
                }
                if (relPath.isNotBlank()) {
                    val resolved = File(pkgDir, relPath).canonicalFile
                    if (resolved.exists()) return resolved
                }
            } catch (_: Exception) {}
        }
        val candidates = listOf(
            File(pkgDir, "dist/index.js"),
            File(pkgDir, "bundle/gemini.js"),
            File(pkgDir, "index.js"),
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun ensureGeminiWrapper(paths: BootstrapInstaller.Paths) {
        val prefix = paths.prefixDir
        val entry = resolveGeminiEntry(prefix) ?: return
        if (!isValidJsScript(entry)) return
        val geminiBin = File(prefix, "bin/gemini")
        // Wajib unlink/delete symlink terlebih dahulu agar writeText tidak menimpa file JS gemini!
        try { geminiBin.delete() } catch (_: Exception) {}
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

        try {
            copyAssetToHome(context, "bionic-compat.js", "${paths.homeDir}/bionic-compat.js")
            copyAssetToHome(context, "proxy.js", "${paths.homeDir}/proxy.js")
            copyAssetToHome(context, "studio-bridge.js", "${paths.homeDir}/studio-bridge.js")
            copyAssetToHome(context, "pty-bridge.py", "${paths.homeDir}/pty-bridge.py")
            File("${paths.homeDir}/pty-bridge.py").setExecutable(true)
        } catch (e: Exception) {
            appendLog("[4/4] Peringatan sinkronisasi aset jembatan: ${e.message}")
        }

        if (!isProcessAlive(proxyProcess)) {
            val pbProxy = ProcessBuilder(nodeBin, "${paths.homeDir}/proxy.js")
            pbProxy.environment().clear()
            pbProxy.environment().putAll(env)
            pbProxy.directory(File(paths.homeDir))
            pbProxy.redirectErrorStream(true)
            proxyProcess = pbProxy.start()
        }

        if (!isProcessAlive(bridgeProcess)) {
            isIntentionalShutdown = false
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
            scope.launch(Dispatchers.IO) {
                try {
                    val exitCode = proc.waitFor()
                    if (!isIntentionalShutdown) {
                        appendLog("[watchdog] Bridge exited unexpectedly (code=$exitCode).")
                        _isServerRunning.value = false
                        val now = System.currentTimeMillis()
                        val shouldRestart = synchronized(restartTimestamps) {
                            restartTimestamps.removeAll { now - it > 60_000L }
                            if (restartTimestamps.size < 5) {
                                restartTimestamps.add(now)
                                true
                            } else {
                                false
                            }
                        }
                        if (shouldRestart) {
                            delay(800)
                            if (!isIntentionalShutdown) {
                                appendLog("[watchdog] Auto-reviving bridge server...")
                                ensureServerRunning()
                            }
                        } else {
                            appendLog("[watchdog] Rate limit reached (max 5 restarts/60s). Skipping auto-revive.")
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ── Bridge HTTP / SSE Client ───────────────────────────────────────────────

    private fun httpRequest(
        method: String,
        path: String,
        jsonBody: String? = null,
        timeoutMs: Int = 15000,
        allowRevive: Boolean = true
    ): JSONObject {
        return try {
            executeHttpRequest(method, path, jsonBody, timeoutMs)
        } catch (e: Exception) {
            val isConnError = e is java.net.ConnectException || e is java.io.EOFException
            if (allowRevive && isConnError && !isIntentionalShutdown) {
                appendLog("[runtime] Connection error (${e.javaClass.simpleName}) on $path. Reviving server...")
                if (ensureServerRunning()) {
                    return executeHttpRequest(method, path, jsonBody, timeoutMs)
                }
            }
            throw e
        }
    }

    private fun executeHttpRequest(method: String, path: String, jsonBody: String?, timeoutMs: Int): JSONObject {
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
            val res = httpRequest("GET", "/health", timeoutMs = 4000, allowRevive = false)
            val authOk = res.optBoolean("authenticated", false) || res.optBoolean("logged_in", false)
            _isLoggedIn.value = authOk
            val sid = res.optString("active_session_id", "")
            if (sid.isNotBlank()) {
                _activeSessionId.value = sid
            }
            val ws = res.optString("active_workspace", "")
            if (ws.isNotBlank() && _activeWorkspace.value.isBlank()) {
                _activeWorkspace.value = ws
            }
            _isServerRunning.value = (res.optString("status") == "ok")
            res
        } catch (_: Exception) {
            _isServerRunning.value = false
            null
        }
    }

    fun shutdownServerCleanly() {
        isIntentionalShutdown = true
        try {
            bridgeProcess?.let { proc ->
                if (isProcessAlive(proc)) {
                    proc.destroy()
                    Thread.sleep(250)
                    if (isProcessAlive(proc)) {
                        proc.destroyForcibly()
                    }
                }
            }
        } catch (_: Exception) {}
        bridgeProcess = null

        try {
            proxyProcess?.let { proc ->
                if (isProcessAlive(proc)) {
                    proc.destroy()
                    Thread.sleep(250)
                    if (isProcessAlive(proc)) {
                        proc.destroyForcibly()
                    }
                }
            }
        } catch (_: Exception) {}
        proxyProcess = null

        _isServerRunning.value = false
        appendLog("[runtime] Bridge server dihentikan dengan aman.")
    }

    fun ensureServerRunning(context: Context? = null): Boolean {
        val ctx = context ?: appContext ?: return false
        setApplicationContext(ctx)
        isIntentionalShutdown = false

        // 1. Cek apakah bridge sudah hidup dan merespons /health
        val currentHealth = refreshHealth()
        if (currentHealth != null && currentHealth.optString("status") == "ok") {
            _isServerRunning.value = true
            return true
        }

        // 2. Jika belum hidup atau mati, pastikan proses lama dibersihkan
        shutdownServerCleanly()
        isIntentionalShutdown = false

        val paths = BootstrapInstaller.getPaths(ctx)
        startBackgroundNodeServers(ctx, paths)

        // 3. Polling health hingga 15 kali (~4.5 detik)
        for (i in 1..15) {
            Thread.sleep(300)
            val h = refreshHealth()
            if (h != null && h.optString("status") == "ok") {
                _isServerRunning.value = true
                return true
            }
        }
        return false
    }

    fun restartServer(context: Context? = null) {
        scope.launch {
            shutdownServerCleanly()
            delay(500)
            isIntentionalShutdown = false
            ensureServerRunning(context)
        }
    }

    fun stopServer() {
        isIntentionalShutdown = true
        scope.launch {
            shutdownServerCleanly()
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

    fun submitAuthCode(code: String): JSONObject {
        return try {
            val body = JSONObject().apply { put("code", code.trim()) }.toString()
            val res = httpRequest("POST", "/auth/code", body, timeoutMs = 30000)
            refreshHealth()
            res
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal mengirim kode autentikasi")
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

    fun renameSession(id: String, newTitle: String): JSONObject {
        return try {
            val body = JSONObject().apply { put("title", newTitle.trim()) }.toString()
            httpRequest("PATCH", "/sessions/$id", body)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal mengubah nama sesi")
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

    fun setActiveWorkspace(name: String) {
        _activeWorkspace.value = name
    }

    fun listWorkspaces(): JSONObject {
        return try {
            httpRequest("GET", "/workspaces")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal memuat workspace")
            }
        }
    }

    fun createWorkspace(name: String, channelId: String? = null): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("name", name)
                if (channelId != null) {
                    put("channel_id", channelId)
                }
            }.toString()
            val res = httpRequest("POST", "/workspaces", body)
            if (res.optString("status") == "ok") {
                _activeWorkspace.value = name
            }
            res
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal membuat workspace")
            }
        }
    }

    // ── Channels API ─────────────────────────────────────────────────────────

    fun listChannels(): JSONObject {
        return try {
            httpRequest("GET", "/channels")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal memuat channel")
            }
        }
    }

    fun createChannel(
        name: String,
        niche: String = "",
        targetAudience: String = "",
        aspectRatio: String = "9:16",
        tone: String = "",
        visualRules: String = "",
        subtitleStyle: String = "",
        styleNotes: String = ""
    ): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("name", name)
                put("niche", niche)
                put("target_audience", targetAudience)
                put("aspect_ratio", aspectRatio)
                put("tone", tone)
                put("visual_rules", visualRules.ifBlank { styleNotes })
                put("subtitle_style", subtitleStyle)
                put("style_notes", styleNotes.ifBlank { visualRules })
            }.toString()
            httpRequest("POST", "/channels", body)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal membuat channel")
            }
        }
    }

    fun updateChannel(
        id: String,
        name: String,
        niche: String = "",
        targetAudience: String = "",
        aspectRatio: String = "9:16",
        tone: String = "",
        visualRules: String = "",
        subtitleStyle: String = "",
        styleNotes: String = ""
    ): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("name", name)
                put("niche", niche)
                put("target_audience", targetAudience)
                put("aspect_ratio", aspectRatio)
                put("tone", tone)
                put("visual_rules", visualRules.ifBlank { styleNotes })
                put("subtitle_style", subtitleStyle)
                put("style_notes", styleNotes.ifBlank { visualRules })
            }.toString()
            httpRequest("PUT", "/channels/$id", body)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal memperbarui channel")
            }
        }
    }

    fun deleteChannel(id: String): JSONObject {
        return try {
            httpRequest("DELETE", "/channels/$id")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal menghapus channel")
            }
        }
    }

    fun saveChannelVoiceProfile(channelId: String, profile: VoiceProfile): JSONObject {
        return try {
            val vpJson = JSONObject().apply {
                put("voice", profile.voice)
                put("rate", profile.rate)
                put("pitch", profile.pitch)
                put("master_preset", profile.masterPreset)
            }
            val body = JSONObject().apply {
                put("dna", JSONObject().apply {
                    put("voice_profile", vpJson)
                })
            }.toString()
            httpRequest("PUT", "/channels/$channelId", body)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal menyimpan voice profile channel")
            }
        }
    }

    fun extractChannelDna(channelId: String, referenceText: String): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("reference_text", referenceText)
            }.toString()
            httpRequest("POST", "/channels/$channelId/extract-dna", body, timeoutMs = 35_000)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal mengekstrak DNA channel")
            }
        }
    }

    fun promoteWorkspaceDna(workspace: String): JSONObject {
        return try {
            httpRequest("POST", "/workspaces/$workspace/promote-dna")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal mempromosikan ke DNA channel")
            }
        }
    }

    // ── Living Patterns API ──────────────────────────────────────────────────

    fun listPatterns(): JSONObject {
        return try {
            httpRequest("GET", "/patterns")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal memuat daftar pola ritme")
            }
        }
    }

    fun createPattern(name: String, aspectRatio: String = "9:16", beatsJsonArray: org.json.JSONArray): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("name", name)
                put("aspect_ratio", aspectRatio)
                put("beats", beatsJsonArray)
            }.toString()
            httpRequest("POST", "/patterns", body)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal membuat pola ritme")
            }
        }
    }

    fun deletePattern(id: String): JSONObject {
        return try {
            httpRequest("DELETE", "/patterns/$id")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal menghapus pola ritme")
            }
        }
    }

    fun saveWorkspaceAsPattern(workspace: String, patternName: String): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("name", patternName)
            }.toString()
            httpRequest("POST", "/workspaces/$workspace/save-as-pattern", body)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal menyimpan pola dari proyek")
            }
        }
    }

    fun applyPattern(workspace: String, patternId: String): Result<JSONObject> {
        return try {
            val body = JSONObject().apply {
                put("pattern_id", patternId)
            }.toString()
            val res = httpRequest("POST", "/workspaces/$workspace/apply-pattern", body)
            if (res.optString("status") == "ok") {
                val proj = res.optJSONObject("project") ?: JSONObject()
                Result.success(proj)
            } else {
                Result.failure(Exception(res.optString("error", "Gagal menerapkan pola")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun syncVoDuration(workspace: String): Result<JSONObject> {
        return try {
            val res = httpRequest("POST", "/workspaces/$workspace/sync-vo-duration", timeoutMs = 60_000)
            if (res.optString("status") == "ok") {
                Result.success(res)
            } else {
                Result.failure(Exception(res.optString("error", "Gagal sinkronisasi durasi audio VO")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Extensions API (Skills & MCP) ────────────────────────────────────────

    fun listExtensionSkills(): List<ExtensionSkillItem> {
        return try {
            val res = httpRequest("GET", "/extensions/skills")
            val arr = res.optJSONArray("skills") ?: return emptyList()
            val list = mutableListOf<ExtensionSkillItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ExtensionSkillItem(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        description = obj.optString("description"),
                        path = obj.optString("path"),
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun listExtensionMcp(): List<ExtensionMcpItem> {
        return try {
            val res = httpRequest("GET", "/extensions/mcp")
            val arr = res.optJSONArray("mcp") ?: return emptyList()
            val list = mutableListOf<ExtensionMcpItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ExtensionMcpItem(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        type = obj.optString("type"),
                        target = obj.optString("target"),
                        status = obj.optString("status")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Workspace Project API ────────────────────────────────────────────────

    fun getWorkspaceProject(workspace: String): JSONObject {
        return try {
            httpRequest("GET", "/workspaces/$workspace/project")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal membaca konfigurasi proyek")
            }
        }
    }

    fun updateWorkspaceProject(workspace: String, projectJson: String): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("project", JSONObject(projectJson))
            }.toString()
            httpRequest("PUT", "/workspaces/$workspace/project", body)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal menyimpan konfigurasi proyek")
            }
        }
    }

    fun getProject(workspace: String): Result<JSONObject> {
        val res = getWorkspaceProject(workspace)
        return if (res.optString("status") == "ok") {
            val proj = res.optJSONObject("project") ?: JSONObject()
            Result.success(proj)
        } else {
            Result.failure(Exception(res.optString("error", "Failed to read project")))
        }
    }

    fun saveProject(workspace: String, project: JSONObject): Result<JSONObject> {
        val res = updateWorkspaceProject(workspace, project.toString())
        return if (res.optString("status") == "ok") {
            val proj = res.optJSONObject("project") ?: JSONObject()
            Result.success(proj)
        } else {
            Result.failure(Exception(res.optString("error", "Failed to save project")))
        }
    }

    fun startRender(
        workspace: String,
        burnSubtitles: Boolean,
        motion: Boolean,
        resolution: String,
        subtitlePreset: String = "safe_916"
    ): Result<JSONObject> {
        return try {
            val body = JSONObject().apply {
                put("burn_subtitles", burnSubtitles)
                put("motion", motion)
                put("resolution", resolution)
                put("subtitle_preset", subtitlePreset)
            }.toString()
            val res = httpRequest("POST", "/workspaces/$workspace/render", body)
            if (res.optString("status") == "ok") {
                Result.success(res)
            } else {
                Result.failure(Exception(res.optString("error", "Render request failed")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun generatePublishKit(workspace: String): Result<JSONObject> {
        return try {
            val res = httpRequest("POST", "/workspaces/$workspace/publish-kit", "{}", timeoutMs = 45_000)
            if (res.optString("status") == "ok") {
                val kit = res.optJSONObject("publish_kit") ?: JSONObject()
                Result.success(kit)
            } else {
                Result.failure(Exception(res.optString("error", "Gagal membuat Publish Kit")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getRenderStatus(workspace: String): Result<JSONObject> {
        return try {
            val res = httpRequest("GET", "/workspaces/$workspace/render/status")
            if (res.has("status")) {
                Result.success(res)
            } else {
                Result.failure(Exception("Invalid render status response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Asset Library API ────────────────────────────────────────────────────

    fun listLibraryAssets(category: String? = null): JSONObject {
        return try {
            val path = if (!category.isNullOrBlank()) "/library?category=$category" else "/library"
            httpRequest("GET", path)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal memuat pustaka aset")
            }
        }
    }

    fun deleteLibraryAsset(category: String, name: String): JSONObject {
        return try {
            httpRequest("DELETE", "/library/$category/$name")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal menghapus aset pustaka")
            }
        }
    }

    fun copyLibraryAssetToWorkspace(
        category: String,
        name: String,
        workspace: String,
        targetDir: String? = null
    ): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("category", category)
                put("file_name", name)
                put("workspace", workspace)
                if (targetDir != null) put("target_dir", targetDir)
            }.toString()
            httpRequest("POST", "/library/copy-to-workspace", body)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal menyalin aset ke proyek")
            }
        }
    }

    fun getLibraryDir(): File {
        val homeDir = appContext?.let { BootstrapInstaller.getPaths(it).homeDir }
            ?: (System.getenv("HOME") ?: "/data/user/0/com.agy/files")
        return File(homeDir, "ContentStudio/library")
    }

    fun detectCategory(mimeType: String?, fileName: String): String {
        val mime = (mimeType ?: "").lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("video/") || ext in listOf("mp4", "mov", "mkv", "webm", "avi") -> "video"
            mime.startsWith("audio/") || ext in listOf("mp3", "m4a", "wav", "aac", "ogg", "flac") -> "audio"
            mime.startsWith("image/") || ext in listOf("png", "jpg", "jpeg", "webp", "gif") -> "image"
            else -> "other"
        }
    }

    fun importToLibrary(context: Context, uri: Uri, explicitCategory: String? = null): Result<File> {
        return runCatching {
            var rawName = "import_${System.currentTimeMillis()}"
            val mimeType = context.contentResolver.getType(uri)
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) rawName = name
                }
            }

            val sanitizedName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cat = if (!explicitCategory.isNullOrBlank() && explicitCategory != "all") {
                explicitCategory.lowercase().trim()
            } else {
                detectCategory(mimeType, sanitizedName)
            }

            val libDir = getLibraryDir()
            val destDir = File(libDir, cat)
            if (!destDir.exists()) destDir.mkdirs()

            var finalFile = File(destDir, sanitizedName)
            if (finalFile.exists()) {
                val dotIdx = sanitizedName.lastIndexOf('.')
                val base = if (dotIdx != -1) sanitizedName.substring(0, dotIdx) else sanitizedName
                val ext = if (dotIdx != -1) sanitizedName.substring(dotIdx) else ""
                var counter = 1
                while (finalFile.exists()) {
                    finalFile = File(destDir, "${base}_$counter$ext")
                    counter++
                }
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(finalFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Gagal membaca file dari input stream")
            finalFile
        }
    }

    fun importFileToLibrary(context: Context, uri: Uri, category: String = "other"): Result<File> {
        return importToLibrary(context, uri, category)
    }

    fun deleteWorkspace(name: String): JSONObject {
        return try {
            val res = httpRequest("DELETE", "/workspaces/$name")
            if (res.optString("status") == "ok") {
                if (_activeWorkspace.value == name) {
                    val remainingRes = listWorkspaces()
                    val arr = remainingRes.optJSONArray("workspaces")
                    if (arr != null && arr.length() > 0) {
                        _activeWorkspace.value = arr.getJSONObject(0).optString("name", "")
                    } else {
                        _activeWorkspace.value = ""
                    }
                }
            }
            res
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal menghapus workspace")
            }
        }
    }

    fun generateTts(
        workspace: String,
        text: String,
        voice: String = "en-US-BrianMultilingualNeural",
        rate: String = "+0%",
        pitch: String = "+0Hz",
    ): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("workspace", workspace)
                put("text", text)
                put("voice", voice)
                put("rate", rate)
                put("pitch", pitch)
            }.toString()
            httpRequest("POST", "/tts", body, timeoutMs = 120_000)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal membuat TTS audio")
            }
        }
    }

    fun masterAudio(workspace: String, fileNameOrRelPath: String, preset: String = "natural"): JSONObject {
        return try {
            val body = JSONObject().apply {
                put("workspace", workspace)
                put("file", fileNameOrRelPath)
                put("preset", preset)
            }.toString()
            httpRequest("POST", "/audio/master", body, timeoutMs = 120_000)
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal mastering audio")
            }
        }
    }

    fun getWorkspaceFiles(name: String): JSONObject {
        return try {
            httpRequest("GET", "/workspaces/$name/files")
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Gagal memuat file workspace")
            }
        }
    }

    fun listWorkspaceFiles(workspace: String): JSONObject = getWorkspaceFiles(workspace)

    fun getWorkspaceDir(workspaceName: String): File {
        val homeDir = appContext?.let { BootstrapInstaller.getPaths(it).homeDir }
            ?: (System.getenv("HOME") ?: "/data/user/0/com.agy/files")
        return File(homeDir, "ContentStudio/workspaces/$workspaceName")
    }

    fun getWorkspaceDir(context: Context, workspaceName: String): File {
        setApplicationContext(context)
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.homeDir, "ContentStudio/workspaces/$workspaceName")
    }

    fun getWorkspaceFile(workspaceName: String, relPath: String): File {
        return File(getWorkspaceDir(workspaceName), relPath)
    }

    fun getWorkspaceFile(context: Context, workspaceName: String, relPath: String): File {
        return File(getWorkspaceDir(context, workspaceName), relPath)
    }

    fun readWorkspaceTextFile(workspace: String, relPath: String): Result<String> {
        return runCatching {
            val wsDir = getWorkspaceDir(workspace).canonicalFile
            val targetFile = File(wsDir, relPath).canonicalFile
            if (!targetFile.path.startsWith(wsDir.path)) {
                error("Akses file di luar workspace dilarang")
            }
            if (!targetFile.exists() || !targetFile.isFile) {
                error("File tidak ditemukan")
            }
            targetFile.readText(Charsets.UTF_8)
        }
    }

    fun readWorkspaceTextFile(context: Context, workspace: String, relPath: String): Result<String> {
        setApplicationContext(context)
        return readWorkspaceTextFile(workspace, relPath)
    }

    fun deleteWorkspaceFile(workspace: String, relPath: String): Boolean {
        return try {
            val wsDir = getWorkspaceDir(workspace).canonicalFile
            val targetFile = File(wsDir, relPath).canonicalFile
            if (!targetFile.path.startsWith(wsDir.path)) {
                return false
            }
            if (targetFile.exists() && targetFile.isFile) {
                targetFile.delete()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun deleteWorkspaceFile(context: Context, workspace: String, relPath: String): Boolean {
        setApplicationContext(context)
        return deleteWorkspaceFile(workspace, relPath)
    }

    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "srt" -> "application/x-subrip"
            "py" -> "text/x-python"
            else -> "application/octet-stream"
        }
    }

    fun exportFileToDownloads(context: Context, sourceFile: File): Result<String> {
        setApplicationContext(context)
        return runCatching {
            if (!sourceFile.exists() || !sourceFile.isFile) {
                error("File sumber tidak ditemukan")
            }
            val fileName = sourceFile.name
            val mimeType = getMimeType(fileName)
            val subDir = "ContentStudio"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$subDir")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Gagal membuat entri di MediaStore Downloads")

                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    } ?: error("Gagal membuka output stream")

                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                "Download/$subDir/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadDir, subDir)
                if (!targetDir.exists()) targetDir.mkdirs()
                val destFile = File(targetDir, fileName)
                sourceFile.inputStream().use { input ->
                    FileOutputStream(destFile).use { out ->
                        input.copyTo(out)
                    }
                }
                try {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destFile.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                } catch (_: Exception) {}
                "Download/$subDir/$fileName"
            }
        }
    }

    fun getWorkspaceAudioDir(context: Context, workspaceName: String): File {
        val dir = File(getWorkspaceDir(context, workspaceName), "audio")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getWorkspaceAssetsDir(context: Context, workspaceName: String): File {
        val dir = File(getWorkspaceDir(context, workspaceName), "assets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getWorkspaceScriptsDir(context: Context, workspaceName: String): File {
        val dir = File(getWorkspaceDir(context, workspaceName), "scripts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun importAssetToWorkspace(context: Context, workspaceName: String, uri: Uri): Result<File> {
        return runCatching {
            val destDir = getWorkspaceAssetsDir(context, workspaceName)
            var fileName = "asset_${System.currentTimeMillis()}"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            val destFile = File(destDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Tidak dapat membuka file")
            destFile
        }
    }

    fun importFileToWorkspaceAssets(context: Context, workspaceName: String, uri: Uri): Result<String> {
        return runCatching {
            val destDir = getWorkspaceAssetsDir(context, workspaceName)
            var rawName = "asset_${System.currentTimeMillis()}"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) rawName = name
                }
            }
            val sanitized = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            var finalFile = File(destDir, sanitized)
            if (finalFile.exists()) {
                val dotIdx = sanitized.lastIndexOf('.')
                val base = if (dotIdx != -1) sanitized.substring(0, dotIdx) else sanitized
                val ext = if (dotIdx != -1) sanitized.substring(dotIdx) else ""
                var counter = 1
                while (finalFile.exists()) {
                    finalFile = File(destDir, "${base}_$counter$ext")
                    counter++
                }
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(finalFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Failed to open file input stream")
            "assets/${finalFile.name}"
        }
    }

    fun importAudioToWorkspace(context: Context, workspaceName: String, uri: Uri): Result<File> {
        return runCatching {
            val destDir = getWorkspaceAudioDir(context, workspaceName)
            var fileName = "import_${System.currentTimeMillis()}.m4a"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            val destFile = File(destDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Tidak dapat membuka file audio")
            destFile
        }
    }

    fun sendChatStream(
        sessionId: String,
        message: String,
        workspace: String? = null,
        mode: String? = "director",
        attachments: List<String> = emptyList(),
        onConnectionCreated: ((HttpURLConnection) -> Unit)? = null,
        onStatus: (String) -> Unit,
        onUiSync: ((scope: String, target: String) -> Unit)? = null,
        onActionPlan: ((ActionPlanCardData) -> Unit)? = null,
        onSuggestions: ((List<String>) -> Unit)? = null,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        var conn: HttpURLConnection? = null
        try {
            val body = JSONObject().apply {
                put("session_id", sessionId)
                put("message", message)
                if (!workspace.isNullOrBlank()) {
                    put("workspace", workspace)
                }
                if (!mode.isNullOrBlank()) {
                    put("mode", mode)
                }
                if (attachments.isNotEmpty()) {
                    val arr = JSONArray()
                    attachments.forEach { arr.put(it) }
                    put("attachments", arr)
                }
            }.toString()

            val c = (URL("$BRIDGE_BASE_URL/chat").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                // Streaming /chat: heartbeat SSE tiap 15 detik, batas tunggu 10 menit (bukan 120-140 detik).
                readTimeout = 600000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream")
            }
            conn = c
            onConnectionCreated?.invoke(c)

            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            if (code == 401) {
                _isLoggedIn.value = false
                refreshHealth()
                onError("Sesi login berakhir. Silakan login ulang.")
                return
            }
            if (code !in 200..299) {
                val errTxt = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                onError(errTxt)
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                var finished = false
                var currentEvent: String? = null
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("event: ")) {
                        currentEvent = trimmed.removePrefix("event: ").trim()
                    } else if (trimmed.startsWith("data: ")) {
                        val jsonStr = trimmed.removePrefix("data: ").trim()
                        if (jsonStr.isNotEmpty()) {
                            try {
                                val obj = JSONObject(jsonStr)
                                val type = obj.optString("type")
                                val text = obj.optString("text", "")
                                if (currentEvent == "ui_sync" || type == "ui_sync") {
                                    val scope = obj.optString("scope", "all")
                                    val target = obj.optString("target", "")
                                    onUiSync?.invoke(scope, target)
                                    incrementUiSyncVersion()
                                    if (scope == "project" && target.isNotBlank()) {
                                        setActiveWorkspace(target)
                                    }
                                } else if (currentEvent == "action_plan" || type == "action_plan") {
                                    val title = obj.optString("title", "Action Plan")
                                    val stepsArr = obj.optJSONArray("steps")
                                    val stepsList = mutableListOf<String>()
                                    if (stepsArr != null) {
                                        for (i in 0 until stepsArr.length()) {
                                            stepsList.add(stepsArr.optString(i))
                                        }
                                    }
                                    val approvePrompt = obj.optString("approvePrompt", "Lanjutkan eksekusi rencana ini sekarang")
                                    onActionPlan?.invoke(
                                        ActionPlanCardData(
                                            title = title,
                                            goal = obj.optString("goal", ""),
                                            steps = stepsList,
                                            approvePrompt = approvePrompt,
                                            status = "pending"
                                        )
                                    )
                                } else if (currentEvent == "suggestions" || type == "suggestions") {
                                    val sugArr = obj.optJSONArray("suggestions")
                                    val sugList = mutableListOf<String>()
                                    if (sugArr != null) {
                                        for (i in 0 until sugArr.length()) {
                                            val s = sugArr.optString(i).trim()
                                            if (s.isNotEmpty()) sugList.add(s)
                                        }
                                    }
                                    if (sugList.isNotEmpty()) {
                                        onSuggestions?.invoke(sugList)
                                    }
                                } else when (type) {
                                    "status" -> {
                                        val stepLabel = text.ifBlank { obj.optString("step", "") }
                                        if (stepLabel.isNotBlank()) onStatus(stepLabel)
                                    }
                                    "delta" -> onDelta(text)
                                    "done" -> {
                                        finished = true
                                        onDone(text)
                                    }
                                    "error" -> {
                                        finished = true
                                        if (text.contains("Sesi login berakhir") || obj.optBoolean("auth_required", false)) {
                                            _isLoggedIn.value = false
                                            refreshHealth()
                                        }
                                        onError(text)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        currentEvent = null
                    } else if (trimmed.isEmpty()) {
                        currentEvent = null
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

    suspend fun getMemories(projectName: String?): Pair<List<MemoryEntry>, List<MemoryEntry>> = withContext(Dispatchers.IO) {
        try {
            val q = if (!projectName.isNullOrBlank()) "?project=${URLEncoder.encode(projectName, "UTF-8")}" else ""
            val conn = URL("$BRIDGE_BASE_URL/memory$q").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(txt)
                val pArr = obj.optJSONArray("projectMemory")
                val gArr = obj.optJSONArray("globalMemory")
                val pList = mutableListOf<MemoryEntry>()
                if (pArr != null) {
                    for (i in 0 until pArr.length()) {
                        val m = pArr.getJSONObject(i)
                        pList.add(
                            MemoryEntry(
                                id = m.optString("id"),
                                note = m.optString("note"),
                                category = m.optString("category", "decision"),
                                createdAt = m.optString("createdAt")
                            )
                        )
                    }
                }
                val gList = mutableListOf<MemoryEntry>()
                if (gArr != null) {
                    for (i in 0 until gArr.length()) {
                        val m = gArr.getJSONObject(i)
                        gList.add(
                            MemoryEntry(
                                id = m.optString("id"),
                                note = m.optString("note"),
                                category = m.optString("category", "decision"),
                                createdAt = m.optString("createdAt")
                            )
                        )
                    }
                }
                Pair(pList, gList)
            } else Pair(emptyList(), emptyList())
        } catch (e: Exception) {
            Pair(emptyList(), emptyList())
        }
    }

    suspend fun addMemory(projectName: String?, note: String, category: String = "decision"): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$BRIDGE_BASE_URL/memory").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().apply {
                if (!projectName.isNullOrBlank()) put("project", projectName)
                put("note", note.trim())
                put("category", category.trim())
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val ok = conn.responseCode == 200
            if (ok) incrementUiSyncVersion()
            ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteMemory(projectName: String?, id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$BRIDGE_BASE_URL/memory").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().apply {
                if (!projectName.isNullOrBlank()) put("project", projectName)
                put("id", id.trim())
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val ok = conn.responseCode == 200
            if (ok) incrementUiSyncVersion()
            ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getProjectContextItems(context: Context, projectName: String?): List<String> = withContext(Dispatchers.IO) {
        val baseItems = mutableListOf("@script", "@scenes", "@channel_dna", "@memory")
        if (!projectName.isNullOrBlank()) {
            try {
                val assetsDir = getWorkspaceAssetsDir(context, projectName)
                if (assetsDir.exists() && assetsDir.isDirectory) {
                    val files = assetsDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
                    files.take(8).forEach { f ->
                        baseItems.add("@assets/${f.name}")
                    }
                }
            } catch (_: Exception) {}
        }
        baseItems
    }

    suspend fun getMediaConfig(): StockMediaConfig? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$BRIDGE_BASE_URL/media/config").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode == 200) {
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(txt)
                StockMediaConfig(
                    pexelsConfigured = obj.optBoolean("pexelsConfigured", false),
                    pixabayConfigured = obj.optBoolean("pixabayConfigured", false),
                    ytdlpReady = obj.optBoolean("ytdlpReady", false),
                    active = obj.optBoolean("active", true)
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveMediaConfig(pexelsKey: String, pixabayKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$BRIDGE_BASE_URL/media/config").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().apply {
                put("pexelsKey", pexelsKey.trim())
                put("pixabayKey", pixabayKey.trim())
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    suspend fun updateYtdlp(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$BRIDGE_BASE_URL/media/update-ytdlp").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 120000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.close()
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    suspend fun searchMedia(
        query: String,
        source: String = "auto",
        type: String = "video",
        orientation: String = "all",
        limit: Int = 8
    ): List<StockMediaItem> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$BRIDGE_BASE_URL/media/search").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 50000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().apply {
                put("query", query)
                put("source", source)
                put("type", type)
                put("orientation", orientation)
                put("limit", limit)
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode == 200) {
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(txt)
                val itemsArr = obj.optJSONArray("items") ?: JSONArray()
                val list = mutableListOf<StockMediaItem>()
                for (i in 0 until itemsArr.length()) {
                    val it = itemsArr.getJSONObject(i)
                    list.add(
                        StockMediaItem(
                            id = it.optString("id"),
                            provider = it.optString("provider"),
                            type = it.optString("type"),
                            title = it.optString("title"),
                            thumbnailUrl = it.optString("thumbnailUrl"),
                            downloadUrl = it.optString("downloadUrl"),
                            width = it.optInt("width", 1080),
                            height = it.optInt("height", 1920),
                            durationSec = it.optInt("durationSec", 0),
                            author = it.optString("author")
                        )
                    )
                }
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun downloadMedia(
        url: String,
        filename: String = "",
        project: String = "",
        audioOnly: Boolean = false,
        start: String = "",
        end: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$BRIDGE_BASE_URL/media/download").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 600000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val body = JSONObject().apply {
                put("url", url)
                if (filename.isNotBlank()) put("filename", filename)
                if (project.isNotBlank()) put("project", project)
                if (audioOnly) put("audioOnly", true)
                if (start.isNotBlank()) put("start", start)
                if (end.isNotBlank()) put("end", end)
            }.toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchStudioMediaIndex(project: String? = null): List<StudioMediaFile> = withContext(Dispatchers.IO) {
        try {
            val q = if (!project.isNullOrBlank()) "?project=${URLEncoder.encode(project, "UTF-8")}" else ""
            val conn = URL("$BRIDGE_BASE_URL/api/media/index$q").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            if (conn.responseCode == 200) {
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(txt)
                val itemsArr = obj.optJSONArray("items") ?: JSONArray()
                val list = mutableListOf<StudioMediaFile>()
                for (i in 0 until itemsArr.length()) {
                    val it = itemsArr.getJSONObject(i)
                    list.add(
                        StudioMediaFile(
                            id = it.optString("id"),
                            name = it.optString("name"),
                            type = it.optString("type"),
                            category = it.optString("category"),
                            project = it.optString("project"),
                            relativePath = it.optString("relativePath"),
                            sizeBytes = it.optLong("sizeBytes", 0L),
                            updatedAt = it.optLong("updatedAt", 0L),
                            streamUrl = it.optString("streamUrl"),
                            thumbUrl = it.optString("thumbUrl")
                        )
                    )
                }
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getFullMediaUrl(streamUrl: String): String {
        return if (streamUrl.startsWith("http://") || streamUrl.startsWith("https://")) {
            streamUrl
        } else {
            "$BRIDGE_BASE_URL$streamUrl"
        }
    }

    fun getFullThumbUrl(media: StudioMediaFile): String {
        val thumb = media.thumbUrl.ifBlank { media.streamUrl }
        return getFullMediaUrl(thumb)
    }

    suspend fun deleteStudioMediaFiles(relativePaths: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (relativePaths.isEmpty()) return@withContext false
            try {
                val conn = (URL("$BRIDGE_BASE_URL/api/media/delete").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val pathsArr = JSONArray()
                relativePaths.forEach { pathsArr.put(it) }
                val body = JSONObject().apply {
                    put("paths", pathsArr)
                }.toString()
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                if (conn.responseCode != 200) {
                    false
                } else {
                    val txt = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(txt)
                    obj.optBoolean("ok", false) && obj.optJSONArray("failed")?.length() == 0
                }
            } catch (e: Exception) {
                false
            }
        }

    fun resolveUriToLocalPath(context: Context, uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            var displayName = "file_${System.currentTimeMillis()}"
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        val n = it.getString(nameIdx)
                        if (!n.isNullOrBlank()) displayName = n
                    }
                }
            }
            val cleanName = displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val attDir = File(context.cacheDir, "attachments")
            attDir.mkdirs()
            val destFile = File(attDir, "${System.currentTimeMillis()}_$cleanName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFile.exists() && destFile.length() > 0) {
                destFile.absolutePath
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun copyUriToAttachmentCache(context: Context, uri: Uri): String? =
        resolveUriToLocalPath(context, uri)
}
