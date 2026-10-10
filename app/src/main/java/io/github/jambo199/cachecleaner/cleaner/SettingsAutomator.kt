package io.github.jambo199.cachecleaner.cleaner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Clears one app's cache the way a person would: open its App info page, go to its storage page
 * and press "Clear cache". Android doesn't let ordinary apps clear other apps' caches directly.
 *
 * It only ever presses the storage row, "Clear cache", and a confirmation that is clearly about
 * the cache. It never presses "Clear data" / "Clear storage", except on Xiaomi phones where
 * "Clear data" just opens a menu, and even there it only picks "Clear cache" from that menu.
 */
class SettingsAutomator(
    private val service: AccessibilityService,
    private val labels: SettingsLabels,
    private val shouldStop: () -> Boolean,
) {
    private val isXiaomi = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** Returns null if the user stopped the clean before this app was finished. */
    suspend fun clearCache(app: AppCacheInfo): Outcome? {
        val previousWindow = service.rootInActiveWindow?.windowId
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            service.startActivity(intent)
        } catch (e: RuntimeException) {
            return Outcome.FAILED
        }

        // Wait for this app's page, not the previous app's page that may still be showing.
        waitFor(LAUNCH_TIMEOUT_MS) { root ->
            !isOwnWindow(root) &&
                (root.windowId != previousWindow || NodeSearch.containsText(root, app.label))
        }
        delay(SETTLE_MS)
        return clearFromAppInfo()
    }

    private suspend fun clearFromAppInfo(): Outcome? {
        val start = SystemClock.uptimeMillis()
        val deadline = start + STEP_TIMEOUT_MS
        var storageClicks = 0
        var lastStorageClick = 0L
        var openedClearDataMenu = false
        var scrolls = 0
        var disabledSince = 0L

        while (SystemClock.uptimeMillis() < deadline) {
            if (shouldStop()) return null
            val root = service.rootInActiveWindow
            if (root == null || isOwnWindow(root)) {
                delay(POLL_MS)
                continue
            }
            val now = SystemClock.uptimeMillis()

            val clearButton = NodeSearch.find(root, labels.clearCache)
            if (clearButton != null) {
                if (NodeSearch.isEnabled(clearButton)) {
                    if (NodeSearch.click(service, clearButton)) return confirmIfAsked(root.windowId)
                } else {
                    // Settings keeps the button disabled until it has measured the app, so only
                    // a button that stays disabled means there is nothing to clear.
                    if (disabledSince == 0L) disabledSince = now
                    if (now - disabledSince > DISABLED_GRACE_MS) return Outcome.ALREADY_EMPTY
                }
                delay(POLL_MS)
                continue
            }
            disabledSince = 0L

            if (storageClicks < 2 && now - lastStorageClick > RETRY_CLICK_MS) {
                val storageRow = NodeSearch.find(root, labels.storage)
                if (storageRow != null && NodeSearch.click(service, storageRow)) {
                    storageClicks++
                    lastStorageClick = now
                    val appInfoWindow = root.windowId
                    waitFor(NAVIGATE_TIMEOUT_MS) {
                        it.windowId != appInfoWindow || NodeSearch.find(it, labels.clearCache) != null
                    }
                    delay(SETTLE_MS)
                    continue
                }
            }

            if (isXiaomi && !openedClearDataMenu) {
                val clearData = NodeSearch.find(root, labels.clearDataMenu)
                if (clearData != null && NodeSearch.click(service, clearData)) {
                    openedClearDataMenu = true
                    delay(SETTLE_MS * 2)
                    continue
                }
            }

            // The storage row can be below the fold on small screens or with large text. Give the
            // page a moment to load first so we don't scroll a still-loading row out of view.
            if (scrolls < MAX_SCROLLS && now - start > SCROLL_AFTER_MS) {
                val list = NodeSearch.findScrollable(root)
                if (list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    scrolls++
                    delay(SETTLE_MS)
                    continue
                }
            }
            delay(POLL_MS)
        }

        // Close the Xiaomi menu (or anything it led to) without choosing anything.
        if (openedClearDataMenu) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return Outcome.FAILED
    }

    /** Some phones ask "Clear cache?" after the button is pressed; most just do it. */
    private suspend fun confirmIfAsked(windowWithButton: Int): Outcome {
        val deadline = SystemClock.uptimeMillis() + CONFIRM_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            delay(POLL_MS)
            val root = service.rootInActiveWindow ?: continue
            if (root.windowId != windowWithButton && !isOwnWindow(root)) {
                val confirm = NodeSearch.find(root, labels.confirm)
                if (confirm != null) {
                    val safe = LabelMatcher.isCacheOnlyDialog(NodeSearch.allText(root), labels.cacheWords, labels.danger)
                    if (!safe) {
                        // Not the dialog we expected: back out rather than confirm something else.
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        return Outcome.FAILED
                    }
                    NodeSearch.click(service, confirm)
                    delay(SETTLE_MS)
                    return Outcome.CLEARED
                }
            }
            // Settings disables the button again once the cache is gone.
            val button = NodeSearch.find(root, labels.clearCache)
            if (button != null && !NodeSearch.isEnabled(button)) return Outcome.CLEARED
        }
        return Outcome.CLEARED
    }

    private fun isOwnWindow(root: AccessibilityNodeInfo) = root.packageName?.toString() == service.packageName

    private suspend fun waitFor(timeoutMs: Long, condition: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline && !shouldStop()) {
            val root = service.rootInActiveWindow
            if (root != null && condition(root)) return
            delay(POLL_MS)
        }
    }

    private companion object {
        const val POLL_MS = 150L
        const val SETTLE_MS = 300L
        const val LAUNCH_TIMEOUT_MS = 2_500L
        const val NAVIGATE_TIMEOUT_MS = 2_000L
        const val STEP_TIMEOUT_MS = 8_000L
        const val DISABLED_GRACE_MS = 2_500L
        const val CONFIRM_TIMEOUT_MS = 2_000L
        const val RETRY_CLICK_MS = 2_000L
        const val SCROLL_AFTER_MS = 1_200L
        const val MAX_SCROLLS = 3
    }
}
