package com.therealityteam.videoplayer.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.download.Downloader
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import com.therealityteam.videoplayer.ui.library.LibraryViewModel
import com.therealityteam.videoplayer.ui.library.busy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Keeps the process FOREGROUND while a library task runs, because the work is hours long and the
 * window is not. Three overnight scrape runs died to exactly this: close the spatial window (or
 * take the headset off long enough), the process drops to background, a long busy scan answers no
 * IPC, and the system kills it as a background ANR — the pass stops wherever it stood and nothing
 * offers to carry on. A dataSync foreground service is the platform's own answer: the scan's
 * lifetime becomes the SERVICE's, not the window's.
 *
 * The service does no work itself. It watches the session's task flows and mirrors them into its
 * notification; when both go quiet it stops itself — so the callers only ever have to START it.
 */
class LibraryTaskService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must reach startForeground quickly after startForegroundService; do it before anything.
        startForeground(NOTIFICATION_ID, notification(getString(R.string.task_notif_title), getString(R.string.task_notif_ready)))
        watchOnce()
        return START_NOT_STICKY // if the process dies anyway, the task died with it — nothing to resume here
    }

    private var watching = false

    private fun watchOnce() {
        if (watching) return
        watching = true
        val vm = runCatching {
            GlobalContext.get().getScope(AURA_SESSION_SCOPE_ID).get<LibraryViewModel>()
        }.getOrNull()
        val downloads = runCatching { GlobalContext.get().get<Downloader>() }.getOrNull()
        if (vm == null && downloads == null) { stopSelf(); return }
        val noTask = kotlinx.coroutines.flow.MutableStateFlow(null)
        scope.launch {
            combine(
                vm?.taskProgress ?: noTask,
                downloads?.active ?: noTask,
            ) { task, download -> task to download }
                .collect { (task, download) ->
                    when {
                        task != null -> notify(
                            getString(task.phase.busy),
                            if (task.indeterminate) getString(R.string.task_notif_walking)
                            else getString(R.string.task_notif_count, task.done, task.total),
                        )
                        // 下载到本机 keeps the process up too: a long copy outlives the window as a scan does.
                        download != null -> notify(
                            getString(R.string.dl_notif_title),
                            if (download.totalBytes > 0) getString(R.string.dl_notif_progress, download.fileName, (download.fraction * 100).toInt())
                            else download.fileName,
                        )
                        else -> {
                            // All quiet: the task set is drained. Drop foreground and stop.
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // Updates the foreground service's own notification, which needs no POST_NOTIFICATIONS: without it the
    // system still runs and lists the service, it only keeps the text out of the shade. The progress is in the app.
    @android.annotation.SuppressLint("NotificationPermission")
    private fun notify(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun notification(title: String, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.task_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scrape)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "library_tasks"
        private const val NOTIFICATION_ID = 41

        /** Idempotent: a second start while running just re-delivers onStartCommand. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, LibraryTaskService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } // a denied start must never break the scan itself — it just loses its shield
        }
    }
}
