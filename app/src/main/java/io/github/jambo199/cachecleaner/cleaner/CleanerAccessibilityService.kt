package io.github.jambo199.cachecleaner.cleaner

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import io.github.jambo199.cachecleaner.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "auto-clean helper". It does nothing until the user presses Clean; then it walks through
 * the chosen apps' Settings pages and presses "Clear cache" on each one.
 */
class CleanerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var stoppedByUser = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    // The clean polls the screen itself, so events are only received to keep Android's view of
    // the screen up to date.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun disconnect() {
        stopRequested = true
        if (instance === this) instance = null
        _connected.value = false
    }

    fun startCleaning(apps: List<AppCacheInfo>) {
        if (job?.isActive == true || apps.isEmpty()) return
        stopRequested = false
        stoppedByUser = false
        CleaningSession.state.value = CleanState.Running(0, apps.size, apps.first().label)
        job = scope.launch { runCleaning(apps) }
    }

    fun stopCleaning() {
        stoppedByUser = true
        stopRequested = true
    }

    private suspend fun runCleaning(apps: List<AppCacheInfo>) {
        val overlay = ProgressOverlay(this, onStop = ::stopCleaning)
        val automator = SettingsAutomator(this, SettingsLabels.load(this), shouldStop = { stopRequested }) { app, kind, text ->
            ActivityLog.add(kind, text, app)
            overlay.showStep(text)
        }
        val keyguard = getSystemService(KeyguardManager::class.java)!!
        val results = ArrayList<AppResult>(apps.size)
        var unsupported = false
        var blocked = false
        var screenLocked = false
        var notOpenedInARow = 0

        ActivityLog.startRun("Started cleaning ${apps.size} ${if (apps.size == 1) "app" else "apps"}")
        overlay.show()
        try {
            for ((index, app) in apps.withIndex()) {
                if (stopRequested) break
                if (keyguard.isKeyguardLocked) {
                    screenLocked = true
                    break
                }
                CleaningSession.state.value = CleanState.Running(index, apps.size, app.label)
                overlay.update(index, apps.size, app.label)
                val outcome = automator.clearCache(app) ?: break
                results += AppResult(app, outcome)

                // Some phones (vivo's "Background pop-ups", Xiaomi's "Display pop-up windows while
                // running in the background") stop the helper from opening Settings pages.
                notOpenedInARow = if (outcome == Outcome.NOT_OPENED) notOpenedInARow + 1 else 0
                if (notOpenedInARow == GIVE_UP_AFTER_NOT_OPENED) {
                    blocked = true
                    break
                }

                // If the first few apps all fail, this phone's Settings screens aren't recognised:
                // stop rather than grind through every app.
                if (results.size == GIVE_UP_AFTER && results.none { it.outcome == Outcome.CLEARED || it.outcome == Outcome.ALREADY_EMPTY }) {
                    unsupported = true
                    break
                }
            }
        } finally {
            overlay.hide()
            val finishedAll = results.size == apps.size && !blocked && !unsupported
            ActivityLog.add(
                if (finishedAll) LogKind.DONE else LogKind.PROBLEM,
                when {
                    blocked -> "Stopped: your phone kept the helper from opening apps' pages"
                    unsupported -> "Stopped: this phone's Settings screens look different from the ones Cache Cleaner knows"
                    screenLocked -> "Stopped because the screen locked"
                    stoppedByUser -> "Stopped after ${results.size} of ${apps.size} apps because you pressed Stop"
                    !finishedAll -> "Stopped after ${results.size} of ${apps.size} apps because the helper was switched off"
                    else -> "Went through all ${apps.size} ${if (apps.size == 1) "app" else "apps"}"
                },
            )
            CleaningSession.state.value = CleanState.Finished(
                results,
                requested = apps.size,
                unsupported = unsupported,
                blocked = blocked,
            )
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
    }

    companion object {
        private const val GIVE_UP_AFTER = 4
        private const val GIVE_UP_AFTER_NOT_OPENED = 3

        @Volatile
        var instance: CleanerAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
