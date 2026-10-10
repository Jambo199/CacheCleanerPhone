package io.github.jambo199.cachecleaner.cleaner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Walks the on-screen view tree by hand. AccessibilityNodeInfo.findAccessibilityNodeInfosByText
 * doesn't see inside Jetpack Compose screens, which newer Settings apps use.
 */
object NodeSearch {

    private const val MAX_NODES = 1500
    private const val MAX_CLIMB = 5
    private const val MAX_DESCRIPTION = 60

    /** The first node whose text or description is one of [labels], preferring visible ones. */
    fun find(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        if (labels.isEmpty()) return null
        var hidden: AccessibilityNodeInfo? = null
        var visible: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (LabelMatcher.matchesAny(node.text, labels) || LabelMatcher.matchesAny(node.contentDescription, labels)) {
                if (node.isVisibleToUser) visible = node else if (hidden == null) hidden = node
            }
            visible == null
        }
        return visible ?: hidden
    }

    fun containsText(root: AccessibilityNodeInfo, text: String): Boolean = find(root, listOf(text)) != null

    /** The words on a button or row as the user sees them, for the activity screen. */
    fun describe(node: AccessibilityNodeInfo): String {
        val text = node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription ?: return "?"
        return text.toString().lineSequence().first().trim().take(MAX_DESCRIPTION)
    }

    /** The first visible node showing one of [sizes], such as a storage row's "231 MB used". */
    fun findSize(root: AccessibilityNodeInfo, sizes: List<String>): AccessibilityNodeInfo? {
        if (sizes.isEmpty()) return null
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (node.isVisibleToUser &&
                (LabelMatcher.mentionsSize(node.text, sizes) || LabelMatcher.mentionsSize(node.contentDescription, sizes))
            ) {
                found = node
            }
            found == null
        }
        return found
    }

    /** All text in the window, one node per line. */
    fun allText(root: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        walk(root) { node ->
            node.text?.let { out.append(it).append('\n') }
            node.contentDescription?.let { out.append(it).append('\n') }
            true
        }
        return out.toString()
    }

    fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (node.isScrollable && node.isVisibleToUser) found = node
            found == null
        }
        return found
    }

    /** Whether the button (and the row or container that receives its clicks) is enabled. */
    fun isEnabled(node: AccessibilityNodeInfo): Boolean {
        node.refresh()
        val target = clickableAncestor(node)
        target?.refresh()
        return node.isEnabled && (target?.isEnabled ?: true)
    }

    /**
     * Clicks [node] through accessibility, or, if [allowTap], taps its centre when nothing around
     * it is clickable.
     */
    fun click(service: AccessibilityService, node: AccessibilityNodeInfo, allowTap: Boolean): Boolean {
        val target = clickableAncestor(node)
        if (target != null && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return allowTap && tap(service, node)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLIMB) {
            val n = current ?: return null
            if (n.isClickable) return n
            current = n.parent
        }
        return null
    }

    private fun tap(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    /** Depth-first walk in on-screen order; [visit] returns false to stop early. */
    private inline fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen++ < MAX_NODES) {
            val node = stack.removeAt(stack.size - 1)
            if (!visit(node)) return
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
    }
}
