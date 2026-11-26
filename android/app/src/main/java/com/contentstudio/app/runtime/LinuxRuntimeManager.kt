package com.contentstudio.app.runtime

import android.content.Context
import android.net.Uri
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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

data class WorkspaceSummary(
    val name: String,
    val path: String,
    val filesCount: Int,
    val updatedAt: Long,
)

data class WorkspaceFileItem(
    val name: String,
    val relPath: String,
    val size: Long,
    val category: String,
    val updatedAt: Long,
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

    private val _activeWorkspace = MutableStateFlow("")
    val activeWorkspace: StateFlow<String> = _activeWorkspace.asStateFlow()

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

    fun createWorkspace(name: String): JSONObject {
        return try {
            val body = JSONObject().apply { put("name", name) }.toString()
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

    fun getWorkspaceDir(context: Context, workspaceName: String): File {
        val paths = BootstrapInstaller.getPaths(context)
        return File(paths.homeDir, "ContentStudio/workspaces/$workspaceName")
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

    fun sendChatStream(
        sessionId: String,
        message: String,
        workspace: String? = null,
        onStatus: (String) -> Unit,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            val body = JSONObject().apply {
                put("session_id", sessionId)
                put("message", message)
                if (!workspace.isNullOrBlank()) {
                    put("workspace", workspace)
                }
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
                                        if (text.contains("Sesi login berakhir") || obj.optBoolean("auth_required", false)) {
                                            _isLoggedIn.value = false
                                            refreshHealth()
                                        }
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
