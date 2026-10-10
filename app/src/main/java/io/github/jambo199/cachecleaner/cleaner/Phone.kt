package io.github.jambo199.cachecleaner.cleaner

import android.os.Build

/** Phone makers whose Settings screens or permissions need special handling. */
object Phone {
    private val maker = Build.MANUFACTURER.lowercase()

    /** Also covers iQOO phones, which report vivo as their maker. */
    val isVivo = maker == "vivo"

    val isXiaomi = maker in setOf("xiaomi", "redmi", "poco")
}
