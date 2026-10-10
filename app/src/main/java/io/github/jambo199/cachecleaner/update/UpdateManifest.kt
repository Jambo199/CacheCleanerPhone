package io.github.jambo199.cachecleaner.update

/**
 * The small version.json that each GitHub build publishes next to the APK, saying which version
 * it is. Kept free of Android types so it can be unit tested.
 */
data class UpdateManifest(val versionCode: Long, val versionName: String) {

    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    companion object {
        private val VERSION_CODE = Regex("\"versionCode\"\\s*:\\s*(\\d+)")
        private val VERSION_NAME = Regex("\"versionName\"\\s*:\\s*\"([^\"]*)\"")

        /** Reads `{"versionCode": 107, "versionName": "1.5.7"}`, or returns null if it isn't one. */
        fun parse(json: String): UpdateManifest? {
            val code = VERSION_CODE.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val name = VERSION_NAME.find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: code.toString()
            return UpdateManifest(code, name)
        }
    }
}
