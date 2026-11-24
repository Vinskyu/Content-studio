package com.contentstudio.app.data

import android.content.Context
import android.content.SharedPreferences
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object PythonHost {

    private const val PREFS_NAME = "contentstudio_prefs"
    private var isInitialized = false
    private lateinit var bridgeModule: PyObject

    fun ensureInitialized(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return

            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }

            val py = Python.getInstance()
            bridgeModule = py.getModule("engine.bridge")

            val appRoot = extractAssetsBundle(context)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val ffmpegBin = File(nativeLibDir, "libffmpeg.so").absolutePath
            val ffprobeBin = File(nativeLibDir, "libffprobe.so").absolutePath

            val outDir = prefs.getString("out_dir", "/sdcard/ContentStudio/out") ?: "/sdcard/ContentStudio/out"

            val envMap = mutableMapOf<String, String>()
            listOf("GROQ_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY").forEach { key ->
                val v = prefs.getString(key, "") ?: ""
                if (v.isNotBlank()) {
                    envMap[key] = v
                }
            }
            val envJson = JSONObject(envMap as Map<*, *>).toString()

            // Memanggil bridge.init_env(root_dir, out_dir, ffmpeg_bin, ffprobe_bin, env_json)
            bridgeModule.callAttr(
                "init_env",
                appRoot.absolutePath,
                outDir,
                ffmpegBin,
                ffprobeBin,
                envJson
            )

            isInitialized = true
        }
    }

    private fun extractAssetsBundle(context: Context): File {
        val rootDir = File(context.filesDir, "ContentStudio")
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }

        fun copyAssetFolder(assetPath: String, targetDir: File) {
            val assetManager = context.assets
            val list = assetManager.list(assetPath) ?: return
            if (list.isEmpty()) {
                // Merupakan file
                if (!targetDir.exists()) {
                    targetDir.parentFile?.mkdirs()
                    assetManager.open(assetPath).use { input ->
                        FileOutputStream(targetDir).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } else {
                // Merupakan direktori
                if (!targetDir.exists()) targetDir.mkdirs()
                for (file in list) {
                    val subAsset = if (assetPath.isEmpty()) file else "$assetPath/$file"
                    val subTarget = File(targetDir, file)
                    copyAssetFolder(subAsset, subTarget)
                }
            }
        }

        copyAssetFolder("bundle", rootDir)
        return rootDir
    }

    fun runConsole(
        context: Context,
        inputText: String,
        attachedFilesJson: String = "[]",
        onEvent: ((JSONObject) -> Unit)? = null
    ): JSONObject {
        ensureInitialized(context)
        val pyCallback = if (onEvent != null) {
            object {
                fun invoke(eventData: Any?) {
                    try {
                        val jsonStr = when (eventData) {
                            is PyObject -> eventData.toString()
                            is String -> eventData
                            is Map<*, *> -> JSONObject(eventData).toString()
                            else -> eventData?.toString() ?: "{}"
                        }
                        val json = JSONObject(jsonStr)
                        onEvent(json)
                    } catch (_: Exception) {
                    }
                }
            }
        } else null

        val resultStr = if (pyCallback != null) {
            bridgeModule.callAttr("run_console", inputText, attachedFilesJson, pyCallback).toString()
        } else {
            bridgeModule.callAttr("run_console", inputText, attachedFilesJson).toString()
        }
        return JSONObject(resultStr)
    }

    fun getSessionState(context: Context): JSONObject {
        ensureInitialized(context)
        val resultStr = bridgeModule.callAttr("get_session_state").toString()
        return JSONObject(resultStr)
    }

    fun listWorkspaces(context: Context): JSONObject {
        ensureInitialized(context)
        val resultStr = bridgeModule.callAttr("list_workspaces").toString()
        return JSONObject(resultStr)
    }

    fun listAssets(context: Context, type: String = "", tag: String = ""): JSONObject {
        ensureInitialized(context)
        val resultStr = bridgeModule.callAttr("list_assets", type, tag).toString()
        return JSONObject(resultStr)
    }

    fun scanAssets(context: Context): JSONObject {
        ensureInitialized(context)
        val resultStr = bridgeModule.callAttr("scan_assets").toString()
        return JSONObject(resultStr)
    }

    fun saveApiKeys(context: Context, groq: String, gemini: String, openRouter: String, outDir: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("GROQ_API_KEY", groq.trim())
            putString("GEMINI_API_KEY", gemini.trim())
            putString("OPENROUTER_API_KEY", openRouter.trim())
            putString("out_dir", outDir.trim())
            apply()
        }
        isInitialized = false
        ensureInitialized(context)
    }

    fun getApiKeys(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return mapOf(
            "GROQ_API_KEY" to (prefs.getString("GROQ_API_KEY", "") ?: ""),
            "GEMINI_API_KEY" to (prefs.getString("GEMINI_API_KEY", "") ?: ""),
            "OPENROUTER_API_KEY" to (prefs.getString("OPENROUTER_API_KEY", "") ?: ""),
            "out_dir" to (prefs.getString("out_dir", "/sdcard/ContentStudio/out") ?: "/sdcard/ContentStudio/out")
        )
    }
}
