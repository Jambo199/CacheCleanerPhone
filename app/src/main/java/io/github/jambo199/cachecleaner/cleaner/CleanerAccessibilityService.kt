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
        CleaningSession.state.value = CleanState.Running(0, apps.size, apps.first().label)
        job = scope.launch { runCleaning(apps) }
    }

    fun stopCleaning() {
        stopRequested = true
    }

    private suspend fun runCleaning(apps: List<AppCacheInfo>) {
        val automator = SettingsAutomator(this, SettingsLabels.load(this)) { stopRequested }
        val overlay = ProgressOverlay(this, onStop = ::stopCleaning)
        val keyguard = getSystemService(KeyguardManager::class.java)!!
        val results = ArrayList<AppResult>(apps.size)
        var unsupported = false

        overlay.show()
        try {
            for ((index, app) in apps.withIndex()) {
                if (stopRequested || keyguard.isKeyguardLocked) break
                CleaningSession.state.value = CleanState.Running(index, apps.size, app.label)
                overlay.update(index, apps.size, app.label)
                val outcome = automator.clearCache(app) ?: break
                results += AppResult(app, outcome)

                // If the first few apps all fail, this phone's Settings screens aren't recognised:
                // stop rather than grind through every app.
                if (results.size == GIVE_UP_AFTER && results.all { it.outcome == Outcome.FAILED }) {
                    unsupported = true
                    break
                }
            }
        } finally {
            overlay.hide()
            CleaningSession.state.value = CleanState.Finished(results, requested = apps.size, unsupported = unsupported)
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

        @Volatile
        var instance: CleanerAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
