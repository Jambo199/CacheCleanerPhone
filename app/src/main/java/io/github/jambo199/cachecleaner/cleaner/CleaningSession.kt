package io.github.jambo199.cachecleaner.cleaner

import kotlinx.coroutines.flow.MutableStateFlow

data class AppCacheInfo(
    val packageName: String,
    val label: String,
    /** Size of the app's cache in bytes, or [UNKNOWN] without usage access. */
    val cacheBytes: Long,
) {
    companion object {
        const val UNKNOWN = -1L
    }
}

enum class Outcome {
    CLEARED,
    ALREADY_EMPTY,
    FAILED,

    /** The app's Settings page never appeared, e.g. because the phone blocked opening it. */
    NOT_OPENED,

    /** "Clear cache" was pressed, but the app still holds most of its cache (it was probably open). */
    STILL_HAS_CACHE,
}

data class AppResult(val app: AppCacheInfo, val outcome: Outcome)

sealed interface CleanState {
    data object Idle : CleanState

    data class Running(val done: Int, val total: Int, val current: String) : CleanState

    data class Finished(
        val results: List<AppResult>,
        val requested: Int,
        /** The first few apps all failed, so this phone's Settings screens weren't recognised. */
        val unsupported: Boolean,
        /** Several apps' Settings pages in a row didn't open: the phone is blocking the helper. */
        val blocked: Boolean,
    ) : CleanState
}

/** Shared between the helper service, which runs a clean, and the screen that shows it. */
object CleaningSession {
    val state = MutableStateFlow<CleanState>(CleanState.Idle)
}
