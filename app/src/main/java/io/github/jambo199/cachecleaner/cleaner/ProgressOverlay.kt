package io.github.jambo199.cachecleaner.cleaner

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A small bar at the top of the screen while a clean runs, showing progress and a Stop button.
 * Accessibility services may draw this kind of window without the "display over other apps"
 * permission. It also keeps the screen on until the clean is done.
 */
class ProgressOverlay(
    private val service: AccessibilityService,
    private val onStop: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)!!
    private var root: View? = null
    private var title: TextView? = null
    private var bar: ProgressBar? = null

    fun show() {
        if (root != null) return
        val statusText = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 2
            text = "Getting ready…"
        }
        val progressBar = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
        }
        val stopButton = Button(service).apply {
            text = "Stop"
            setOnClickListener {
                isEnabled = false
                text = "Stopping…"
                onStop()
            }
        }
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                cornerRadius = dp(18).toFloat()
            }
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            })
        }
        val container = FrameLayout(service).apply {
            setPadding(dp(12), 0, dp(12), 0)
            addView(card)
        }

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = statusBarHeight() + dp(8)
        }
        try {
            windowManager.addView(container, params)
            root = container
            title = statusText
            bar = progressBar
        } catch (e: RuntimeException) {
            // The overlay is a nicety; cleaning works without it.
        }
    }

    fun update(done: Int, total: Int, current: String) {
        title?.text = "Clearing cache ${done + 1} of $total\n$current"
        bar?.progress = done * 100 / total.coerceAtLeast(1)
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        title = null
        bar = null
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        }
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) service.resources.getDimensionPixelSize(id) else dp(24)
    }

    private companion object {
        val BACKGROUND = Color.argb(240, 32, 33, 36)
        val ACCENT = Color.rgb(52, 211, 153)
    }
}
