package io.github.jambo199.cachecleaner

import android.app.Application
import android.content.Context
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jambo199.cachecleaner.background.BackgroundClean
import io.github.jambo199.cachecleaner.background.BackgroundCleanState
import io.github.jambo199.cachecleaner.background.ShizukuAccess
import io.github.jambo199.cachecleaner.background.ShizukuStatus
import io.github.jambo199.cachecleaner.cleaner.ActivityLog
import io.github.jambo199.cachecleaner.cleaner.AppCacheInfo
import io.github.jambo199.cachecleaner.cleaner.CleanState
import io.github.jambo199.cachecleaner.cleaner.CleanerAccessibilityService
import io.github.jambo199.cachecleaner.cleaner.CleaningSession
import io.github.jambo199.cachecleaner.cleaner.LogKind
import io.github.jambo199.cachecleaner.cleaner.Outcome
import io.github.jambo199.cachecleaner.update.UpdateManifest
import io.github.jambo199.cachecleaner.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.IOException

data class RunSummary(
    val cleared: Int,
    val alreadyEmpty: Int,
    val failed: List<AppCacheInfo>,
    /** Apps whose cache filled straight back up (usually because they were open). */
    val stillHolding: List<AppCacheInfo>,
    /** Null when we can't measure sizes (no usage access). */
    val freedBytes: Long?,
    val stoppedEarly: Boolean,
    val unsupported: Boolean,
    val blocked: Boolean,
)

/** How the app list is ordered. */
enum class SortOrder(val label: String) {
    MOST_CACHE("Most cache first"),
    LEAST_CACHE("Least cache first"),
    NAME("Name (A–Z)"),
    ;

    fun sort(apps: List<AppCacheInfo>): List<AppCacheInfo> = when (this) {
        MOST_CACHE -> apps.sortedWith(compareByDescending<AppCacheInfo> { it.cacheBytes }.thenBy { it.label.lowercase() })
        LEAST_CACHE -> apps.sortedWith(compareBy<AppCacheInfo> { it.cacheBytes }.thenBy { it.label.lowercase() })
        NAME -> apps.sortedBy { it.label.lowercase() }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    /** [hint] explains what to do next, e.g. after being sent to allow installs. */
    data class Available(val manifest: UpdateManifest, val hint: String? = null) : UpdateState

    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateState

    /** [offerDownloadPage]: the browser, where the user may be signed in, can still get it. */
    data class Failed(val message: String, val offerDownloadPage: Boolean) : UpdateState
}

data class UiState(
    val hasUsageAccess: Boolean = false,
    val helperRunning: Boolean = false,
    val helperSwitchedOn: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    /** Hide the Settings pages behind a progress screen while cleaning. */
    val coverScreen: Boolean = true,
    val sort: SortOrder = SortOrder.MOST_CACHE,
    val autoUpdate: Boolean = true,
    val canInstallUpdates: Boolean = false,
    val versionName: String = "",
    val update: UpdateState = UpdateState.Idle,
    /** Whether background cleaning through Shizuku can be used, or which setup step is next. */
    val shizuku: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
    val background: BackgroundCleanState = BackgroundCleanState.Idle,
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

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(
            coverScreen = prefs.getBoolean(KEY_COVER_SCREEN, true),
            sort = SortOrder.entries.firstOrNull { it.name == prefs.getString(KEY_SORT, null) } ?: SortOrder.MOST_CACHE,
            autoUpdate = prefs.getBoolean(KEY_AUTO_UPDATE, true),
            versionName = Updater.installedVersionName(application),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    // Shizuku tells us when it starts, stops, or the user answers its permission prompt.
    private val shizukuStarted = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val shizukuStopped = Shizuku.OnBinderDeadListener { refreshShizuku() }
    private val shizukuAnswered = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }

    init {
        viewModelScope.launch(Dispatchers.IO) { ActivityLog.load(getApplication<Application>()) }
        Shizuku.addBinderReceivedListenerSticky(shizukuStarted)
        Shizuku.addBinderDeadListener(shizukuStopped)
        Shizuku.addRequestPermissionResultListener(shizukuAnswered)
        viewModelScope.launch {
            BackgroundClean.state.collect { background ->
                val finished = _state.value.background == BackgroundCleanState.Running &&
                    background != BackgroundCleanState.Running
                _state.update { it.copy(background = background) }
                if (finished) scan()
            }
        }
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
                canInstallUpdates = Updater.canInstall(context),
                shizuku = ShizukuAccess.status(context),
            )
        }
        if (!before.scanned || usage != before.hasUsageAccess) scan()

        val lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        if (_state.value.autoUpdate && System.currentTimeMillis() - lastCheck > AUTO_CHECK_EVERY_MS) {
            checkForUpdates(manual = false)
        }
    }

    /**
     * Asks GitHub for a newer version. Automatic checks stay quiet unless they find one; a check
     * from the Settings page always says how it went.
     */
    fun checkForUpdates(manual: Boolean) {
        val current = _state.value.update
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        // Noted before asking, so coming back to the app mid-check doesn't start a second one.
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
        viewModelScope.launch {
            if (manual) _state.update { it.copy(update = UpdateState.Checking) }
            val result = withContext(Dispatchers.IO) { Updater.check() }
            val installed = Updater.installedVersionCode(getApplication<Application>())
            val next = when (result) {
                is Updater.CheckResult.Found ->
                    if (result.manifest.isNewerThan(installed)) UpdateState.Available(result.manifest) else UpdateState.UpToDate
                is Updater.CheckResult.Failed -> UpdateState.Failed(result.reason, offerDownloadPage = result.needsSignIn)
            }
            _state.update { state ->
                val quietFailure = !manual && next !is UpdateState.Available
                state.copy(update = if (quietFailure) state.update else next)
            }
        }
    }

    /** Downloads the update and hands it to Android's installer, which asks the user to confirm. */
    fun installUpdate() {
        val context = getApplication<Application>()
        val manifest = when (val current = _state.value.update) {
            is UpdateState.Available -> current.manifest
            else -> return
        }
        if (!Updater.canInstall(context)) {
            Updater.openInstallPermission(context)
            _state.update {
                it.copy(
                    update = UpdateState.Available(
                        manifest,
                        hint = "Allow Cache Cleaner to install apps on the screen that just opened, then tap Update again.",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(update = UpdateState.Downloading(manifest, 0f)) }
            val next = try {
                val apk = withContext(Dispatchers.IO) {
                    var shown = 0
                    Updater.download(context) { progress ->
                        // Only redraw for whole-percent changes.
                        val percent = (progress * 100).toInt()
                        if (percent != shown) {
                            shown = percent
                            _state.update { it.copy(update = UpdateState.Downloading(manifest, progress)) }
                        }
                    }
                }
                val problem = withContext(Dispatchers.IO) { Updater.verify(context, apk) }
                when {
                    problem != null -> UpdateState.Failed(problem, offerDownloadPage = false)
                    Updater.install(context, apk) -> UpdateState.Available(
                        manifest,
                        hint = "Tap Update on Android's screen to finish. Cache Cleaner restarts with the new version.",
                    )
                    else -> UpdateState.Failed("Android couldn't open its installer.", offerDownloadPage = true)
                }
            } catch (e: IOException) {
                UpdateState.Failed(
                    "The download didn't finish. Check your internet connection and try again.",
                    offerDownloadPage = true,
                )
            }
            _state.update { it.copy(update = next) }
        }
    }

    private fun refreshShizuku() {
        _state.update { it.copy(shizuku = ShizukuAccess.status(getApplication<Application>())) }
    }

    fun requestShizukuPermission() = ShizukuAccess.requestPermission()

    /** Clears every app's cache in the background through Shizuku. Returns false if it isn't ready. */
    fun startBackgroundClean(): Boolean {
        val context = getApplication<Application>()
        if (ShizukuAccess.status(context) != ShizukuStatus.READY) {
            refreshShizuku()
            return false
        }
        if (_state.value.background == BackgroundCleanState.Running) return true
        _state.update { it.copy(lastRun = null) }
        BackgroundClean.start(context)
        return true
    }

    fun dismissBackgroundResult() {
        if (BackgroundClean.state.value != BackgroundCleanState.Running) {
            BackgroundClean.state.value = BackgroundCleanState.Idle
        }
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(shizukuStarted)
        Shizuku.removeBinderDeadListener(shizukuStopped)
        Shizuku.removeRequestPermissionResultListener(shizukuAnswered)
        super.onCleared()
    }

    fun setAutoUpdate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
        _state.update { it.copy(autoUpdate = enabled) }
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
    fun startCleaning(): Boolean = startCleaning(_state.value.selectedApps)

    /** Cleans just [packages] again with the helper, e.g. apps that still held cache. */
    fun cleanAgain(packages: List<String>): Boolean {
        val apps = _state.value.apps.associateBy { it.packageName }
        return startCleaning(packages.mapNotNull { apps[it] })
    }

    private fun startCleaning(targets: List<AppCacheInfo>): Boolean {
        val service = CleanerAccessibilityService.instance ?: return false
        if (targets.isEmpty()) return false
        // Our own cache we can simply delete.
        getApplication<Application>().cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        _state.update { it.copy(lastRun = null) }
        dismissBackgroundResult()
        service.startCleaning(targets, coverScreen = _state.value.coverScreen)
        return true
    }

    fun stopCleaning() {
        CleanerAccessibilityService.instance?.stopCleaning()
    }

    fun dismissResult() = _state.update { it.copy(lastRun = null) }

    fun setSort(sort: SortOrder) {
        prefs.edit().putString(KEY_SORT, sort.name).apply()
        _state.update { it.copy(sort = sort) }
    }

    fun setCoverScreen(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COVER_SCREEN, enabled).apply()
        _state.update { it.copy(coverScreen = enabled) }
    }

    fun clearActivity() {
        viewModelScope.launch(Dispatchers.IO) { ActivityLog.clear(getApplication<Application>()) }
    }

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
                stillHolding = run.results.filter { it.outcome == Outcome.STILL_HAS_CACHE }.map { it.app },
                freedBytes = freed,
                stoppedEarly = run.results.size < run.requested && !run.unsupported && !run.blocked,
                unsupported = run.unsupported,
                blocked = run.blocked,
            )
            _state.update { it.copy(lastRun = summary) }
            when {
                freed == null -> ActivityLog.add(LogKind.SKIPPED, "Allow usage access to see how much space was freed")
                freed > 0 -> ActivityLog.add(
                    LogKind.DONE,
                    "Freed ${Formatter.formatShortFileSize(getApplication<Application>(), freed)} in total",
                )
                else -> ActivityLog.add(LogKind.SKIPPED, "No space was freed this time")
            }
            withContext(Dispatchers.IO) { ActivityLog.save(getApplication<Application>()) }
            scan()
        }
    }

    private companion object {
        const val KEY_COVER_SCREEN = "cover_screen"
        const val KEY_SORT = "sort"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"

        /** Automatic update checks happen at most this often. */
        const val AUTO_CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}
