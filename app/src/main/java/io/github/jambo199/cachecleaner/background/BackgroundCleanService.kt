package io.github.jambo199.cachecleaner.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.jambo199.cachecleaner.MainActivity
import io.github.jambo199.cachecleaner.R
import io.github.jambo199.cachecleaner.cleaner.ActivityLog
import io.github.jambo199.cachecleaner.cleaner.LogKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackgroundCleanState {
    data object Idle : BackgroundCleanState

    data object Running : BackgroundCleanState

    data class Done(val freedBytes: Long) : BackgroundCleanState

    data class Failed(val message: String) : BackgroundCleanState
}

/** Shared between the service, which runs a background clean, and the screen that shows it. */
object BackgroundClean {
    val state = MutableStateFlow<BackgroundCleanState>(BackgroundCleanState.Idle)

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, BackgroundCleanService::class.java))
    }
}

/**
 * Clears every app's cache in the background through Shizuku, showing a notification while it
 * works and another with the result. The phone can be used normally meanwhile.
 */
class BackgroundCleanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            PROGRESS_ID,
            notification("Clearing every app's cache…", "You can keep using your phone.", ongoing = true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0,
        )
        if (BackgroundClean.state.value != BackgroundCleanState.Running) {
            BackgroundClean.state.value = BackgroundCleanState.Running
            scope.launch {
                finish(clean())
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Android 14+ gives short services about three minutes; a clean takes seconds, so this only
    // happens if something is stuck.
    override fun onTimeout(startId: Int) {
        finish(BackgroundCleanState.Failed("The background clean took too long and was stopped."))
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (BackgroundClean.state.value == BackgroundCleanState.Running) {
            BackgroundClean.state.value = BackgroundCleanState.Failed("The background clean was interrupted.")
        }
        super.onDestroy()
    }

    private suspend fun clean(): BackgroundCleanState {
        ActivityLog.startRun("Started a background clean of every app, using Shizuku")
        if (ShizukuAccess.status(this) != ShizukuStatus.READY) {
            return BackgroundCleanState.Failed("Shizuku isn't running. Open Shizuku and start it, then try again.")
        }
        return withContext(Dispatchers.IO) {
            val before = freeSpace()
            val result = runCatching { ShizukuAccess.run(arrayOf("pm", "trim-caches", TRIM_TARGET), COMMAND_TIMEOUT_SECONDS) }
            val freed = (freeSpace() - before).coerceAtLeast(0)
            val outcome = result.getOrNull()
            when {
                outcome == null -> BackgroundCleanState.Failed("Couldn't talk to Shizuku. Open Shizuku, make sure it's running, then try again.")
                outcome.exitCode != 0 -> BackgroundCleanState.Failed(
                    "Android didn't clear the caches" + (outcome.output.takeIf { it.isNotBlank() }?.let { ": ${it.take(200)}" } ?: "."),
                )
                else -> BackgroundCleanState.Done(freed)
            }
        }
    }

    private fun finish(state: BackgroundCleanState) {
        if (BackgroundClean.state.value != BackgroundCleanState.Running) return
        BackgroundClean.state.value = state
        when (state) {
            is BackgroundCleanState.Done -> {
                ActivityLog.add(LogKind.DONE, "Android cleared every app's cache")
                ActivityLog.add(LogKind.DONE, "Freed ${Formatter.formatShortFileSize(this, state.freedBytes)} in total")
                showResult("Cache cleared", "Freed ${Formatter.formatShortFileSize(this, state.freedBytes)}")
            }
            is BackgroundCleanState.Failed -> {
                ActivityLog.add(LogKind.PROBLEM, state.message)
                showResult("Background clean didn't finish", state.message)
            }
            else -> Unit
        }
        // Saved right away: the service stops next, which would cancel anything still pending.
        ActivityLog.save(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun showResult(title: String, text: String) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        getSystemService(NotificationManager::class.java)?.notify(RESULT_ID, notification(title, text, ongoing = false))
    }

    private fun notification(title: String, text: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_broom)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .apply { if (ongoing) setProgress(0, 0, true) }
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cleaning", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and results of background cleans"
            },
        )
    }

    private fun freeSpace(): Long = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(0L)

    private companion object {
        const val CHANNEL_ID = "cleaning"
        const val PROGRESS_ID = 1
        const val RESULT_ID = 2
        const val COMMAND_TIMEOUT_SECONDS = 120L

        // "Make this much space free": far more than any phone has, so Android clears every
        // app's cache to try. It never deletes anything but caches.
        const val TRIM_TARGET = "100000G"
    }
}
