package com.contentstudio.app.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class RuntimeLifecycleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "ContentStudio task removed, executing clean server shutdown")
        try {
            LinuxRuntimeManager.shutdownServerCleanly()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing shutdown onTaskRemoved: ${e.message}", e)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RuntimeLifecycleService destroyed")
    }

    companion object {
        private const val TAG = "RuntimeLifecycleService"
    }
}
