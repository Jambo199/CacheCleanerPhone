package io.github.jambo199.cachecleaner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jambo199.cachecleaner.cleaner.AppCacheInfo
import io.github.jambo199.cachecleaner.cleaner.CleanState
import io.github.jambo199.cachecleaner.cleaner.CleanerAccessibilityService
import io.github.jambo199.cachecleaner.cleaner.CleaningSession
import io.github.jambo199.cachecleaner.cleaner.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RunSummary(
    val cleared: Int,
    val alreadyEmpty: Int,
    val failed: List<AppCacheInfo>,
    /** Null when we can't measure sizes (no usage access). */
    val freedBytes: Long?,
    val stoppedEarly: Boolean,
    val unsupported: Boolean,
    val blocked: Boolean,
)

data class UiState(
    val hasUsageAccess: Boolean = false,
    val helperRunning: Boolean = false,
    val helperSwitchedOn: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val apps: List<AppCacheInfo> = emptyList(),
    val selected: Set<String> = emptySet(),
    val cleaning: CleanState.Running? = null,
    val lastRun: RunSummary? = null,
) {
    val selectedApps: List<AppCacheInfo> get() = apps.filter { it.packageName in selected }
    val totalCacheBytes: Long get() = apps.sumOf { it.cacheBytes.coerceAtLeast(0) }
    val selectedCacheBytes: Long get() = selectedApps.sumOf { it.cacheBytes.coerceAtLeast(0) }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            CleanerAccessibilityService.connected.collect { running ->
                _state.update { it.copy(helperRunning = running) }
            }
        }
        viewModelScope.launch {
            CleaningSession.state.collect { session ->
                _state.update { it.copy(cleaning = session as? CleanState.Running) }
                if (session is CleanState.Finished) {
                    CleaningSession.state.value = CleanState.Idle
                    summarize(session)
                }
            }
        }
    }

    /** Called whenever the screen comes back, as permissions may have changed in Settings. */
    fun refresh() {
        val context = getApplication<Application>()
        val before = _state.value
        val usage = Permissions.hasUsageAccess(context)
        _state.update {
            it.copy(
                hasUsageAccess = usage,
                helperSwitchedOn = Permissions.isHelperEnabled(context),
                hasAllFilesAccess = Permissions.hasAllFilesAccess(),
            )
        }
        if (!before.scanned || usage != before.hasUsageAccess) scan()
    }

    fun scan() {
        if (scanJob?.isActive == true || _state.value.cleaning != null) return
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val apps = withContext(Dispatchers.IO) { AppScanner.scan(getApplication<Application>()) }
            _state.update { old ->
                // Keep the user's ticks for apps already listed; newly listed apps start ticked.
                val known = old.apps.mapTo(HashSet()) { it.packageName }
                val selected = apps
                    .filter { it.packageName !in known || it.packageName in old.selected }
                    .mapTo(HashSet()) { it.packageName }
                old.copy(scanning = false, scanned = true, apps = apps, selected = selected)
            }
        }
    }

    fun toggle(packageName: String) = _state.update {
        val selected = if (packageName in it.selected) it.selected - packageName else it.selected + packageName
        it.copy(selected = selected)
    }

    fun selectAll(all: Boolean) = _state.update {
        it.copy(selected = if (all) it.apps.mapTo(HashSet()) { app -> app.packageName } else emptySet())
    }

    /** Returns false if the helper isn't running or nothing is selected. */
    fun startCleaning(): Boolean {
        val service = CleanerAccessibilityService.instance ?: return false
        val targets = _state.value.selectedApps
        if (targets.isEmpty()) return false
        // Our own cache we can simply delete.
        getApplication<Application>().cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        _state.update { it.copy(lastRun = null) }
        service.startCleaning(targets)
        return true
    }

    fun stopCleaning() {
        CleanerAccessibilityService.instance?.stopCleaning()
    }

    fun dismissResult() = _state.update { it.copy(lastRun = null) }

    private fun summarize(run: CleanState.Finished) {
        viewModelScope.launch {
            // Settings clears caches in the background; give the last app a moment to finish.
            delay(1_000)
            val before = run.results.associate { it.app.packageName to it.app.cacheBytes }
            val after = withContext(Dispatchers.IO) { AppScanner.cacheSizes(getApplication<Application>(), before.keys) }
            val freed = after?.let {
                before.entries.sumOf { (pkg, was) ->
                    val now = it[pkg]
                    if (was < 0 || now == null || now < 0) 0L else (was - now).coerceAtLeast(0)
                }
            }
            val summary = RunSummary(
                cleared = run.results.count { it.outcome == Outcome.CLEARED },
                alreadyEmpty = run.results.count { it.outcome == Outcome.ALREADY_EMPTY },
                failed = run.results
                    .filter { it.outcome == Outcome.FAILED || it.outcome == Outcome.NOT_OPENED }
                    .map { it.app },
                freedBytes = freed,
                stoppedEarly = run.results.size < run.requested && !run.unsupported && !run.blocked,
                unsupported = run.unsupported,
                blocked = run.blocked,
            )
            _state.update { it.copy(lastRun = summary) }
            scan()
        }
    }
}
