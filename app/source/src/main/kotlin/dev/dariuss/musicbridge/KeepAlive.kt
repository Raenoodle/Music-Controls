package dev.dariuss.musicbridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * An ordinary started service (not a foreground one, so nothing shows in the notification shade).
 * While it runs Android ranks the app as a service instead of a cached app, so it is not frozen and
 * media session callbacks keep arriving straight away. It uses no CPU and holds no wakelock.
 */
class KeepAlive : Service() {
    companion object {
        @Volatile
        private var running = false

        /** Starting from the background can be refused; the listener tries again on the next change. */
        fun start(context: Context) {
            if (running) return
            runCatching { context.startService(Intent(context, KeepAlive::class.java)) }
                .onSuccess { running = true }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            runCatching { context.stopService(Intent(context, KeepAlive::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
