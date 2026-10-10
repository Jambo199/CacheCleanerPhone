package io.github.jambo199.cachecleaner.cleaner

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources

/**
 * The words the Settings app uses on its App info screens.
 *
 * Where possible they are read from the Settings app itself, so they match the phone's language;
 * the English spellings used by the common phone makers are always included as a fallback.
 */
class SettingsLabels(
    /** The App info row that leads to the storage page ("Storage & cache", "Storage", ...). */
    val storage: List<String>,
    /** The button we want to press. */
    val clearCache: List<String>,
    /** Xiaomi only: "Clear data" opens a menu that offers "Clear cache". */
    val clearDataMenu: List<String>,
    /** Buttons that accept a "Clear cache?" confirmation. */
    val confirm: List<String>,
    /** A confirmation must mention one of these to be accepted... */
    val cacheWords: List<String>,
    /** ...and none of these. */
    val danger: List<String>,
) {
    companion object {
        private const val AOSP_SETTINGS = "com.android.settings"
        private const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"

        private val ENGLISH_STORAGE = listOf(
            "Storage & cache", "Storage and cache", "Storage", "Storage usage",
        )
        private val ENGLISH_CLEAR_CACHE = listOf(
            "Clear cache", "Clear cached data", "Clear caches", "Delete cache", "Delete cached data",
        )
        private val ENGLISH_CONFIRM = listOf("OK", "Clear", "Clear cache", "Delete", "Confirm", "Yes")
        private val ENGLISH_DANGER = listOf(
            "Clear data", "Clear storage", "Clear all data", "Delete app data", "Delete data",
            "app data", "all data", "Uninstall", "Force stop",
        )

        fun load(context: Context): SettingsLabels {
            val pm = context.packageManager

            val danger = LabelMatcher.distinctLabels(
                lookup(pm, AOSP_SETTINGS, "clear_user_data_text", "clear_data_dlg_title", "force_stop", "uninstall_text") +
                    ENGLISH_DANGER,
            )
            val clearCache = LabelMatcher.distinctLabels(
                lookup(pm, AOSP_SETTINGS, "clear_cache_btn_text") +
                    lookup(pm, MIUI_SECURITY_CENTER, "app_manager_clear_cache") +
                    ENGLISH_CLEAR_CACHE,
            ).filterNot { label -> danger.any { LabelMatcher.normalize(it) == LabelMatcher.normalize(label) } }

            val storage = LabelMatcher.distinctLabels(
                lookup(pm, AOSP_SETTINGS, "storage_settings_for_app", "storage_label", "storage_settings") +
                    ENGLISH_STORAGE,
            )
            val clearDataMenu = LabelMatcher.distinctLabels(
                lookup(pm, MIUI_SECURITY_CENTER, "app_manager_clear_data") + "Clear data",
            )
            val confirm = LabelMatcher.distinctLabels(
                listOfNotNull(systemString(android.R.string.ok)) + clearCache + ENGLISH_CONFIRM,
            )
            return SettingsLabels(
                storage = storage,
                clearCache = clearCache,
                clearDataMenu = clearDataMenu,
                confirm = confirm,
                cacheWords = clearCache + "cach",
                danger = danger,
            )
        }

        private fun systemString(id: Int): String? =
            runCatching { Resources.getSystem().getString(id) }.getOrNull()

        @SuppressLint("DiscouragedApi")
        private fun lookup(pm: PackageManager, pkg: String, vararg names: String): List<String> {
            val res = try {
                pm.getResourcesForApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                return emptyList()
            }
            return names.mapNotNull { name ->
                val id = res.getIdentifier(name, "string", pkg)
                if (id == 0) null else runCatching { res.getString(id) }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
    }
}
