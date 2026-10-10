package io.github.jambo199.cachecleaner.cleaner

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * What the helper did during the latest clean, in plain words. Each new clean starts a fresh log;
 * the last one is saved so it can still be read after the app is closed.
 */
object ActivityLog {

    private const val MAX_ENTRIES = 2_000
    private const val FILE_NAME = "activity_log.tsv"

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(kind: LogKind, text: String, app: AppCacheInfo? = null) {
        val entry = LogEntry(System.currentTimeMillis(), kind, app?.packageName, app?.label, text)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    /** Clears the previous clean's steps and starts a new log with [text]. */
    fun startRun(text: String) {
        _entries.value = emptyList()
        add(LogKind.STEP, text)
    }

    fun clear(context: Context) {
        _entries.value = emptyList()
        save(context)
    }

    /** Saves the log to the app's private storage. Call off the main thread. */
    fun save(context: Context) {
        val lines = _entries.value.joinToString("\n") { LogFormat.encode(it) }
        runCatching { File(context.filesDir, FILE_NAME).writeText(lines) }
    }

    /** Loads the saved log, unless a clean has already started a new one. Call off the main thread. */
    fun load(context: Context) {
        if (_entries.value.isNotEmpty()) return
        val saved = runCatching {
            File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readLines()?.mapNotNull { LogFormat.decode(it) }
        }.getOrNull() ?: return
        _entries.compareAndSet(emptyList(), saved)
    }
}
