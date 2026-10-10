package io.github.jambo199.cachecleaner.cleaner

/**
 * Decides whether an app still holds a real amount of cache after being cleaned. Usually that
 * means the app was open and saved new files straight away. Kept free of Android types so it can
 * be unit tested.
 */
object CacheCheck {

    /** Smaller leftovers are normal: apps recreate empty folders and tiny files immediately. */
    const val MIN_LEFTOVER_BYTES = 1024L * 1024

    /** At least 1 MB left, and at least half of what it had before. */
    fun isStillHolding(beforeBytes: Long, afterBytes: Long): Boolean =
        beforeBytes > 0 && afterBytes >= MIN_LEFTOVER_BYTES && afterBytes * 2 >= beforeBytes

    /** The packages from [before] that [after] shows still holding cache, biggest leftover first. */
    fun stillHolding(before: Map<String, Long>, after: Map<String, Long>): List<String> =
        before.entries
            .mapNotNull { (pkg, was) -> after[pkg]?.takeIf { isStillHolding(was, it) }?.let { pkg to it } }
            .sortedByDescending { it.second }
            .map { it.first }
}
