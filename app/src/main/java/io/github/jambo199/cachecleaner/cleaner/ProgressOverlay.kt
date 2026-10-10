package io.github.jambo199.cachecleaner.cleaner

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.jambo199.cachecleaner.R
import kotlin.math.roundToInt

/**
 * What's on screen while a clean runs. Accessibility services may draw these windows without the
 * "display over other apps" permission, and both keep the screen on until the clean is done.
 *
 * With [coverScreen], a full-screen progress page hides the Settings pages the helper is working
 * through behind it, and swallows taps so nothing in Settings gets pressed by accident. Without
 * it, a small bar at the top shows progress while the Settings pages stay in view.
 */
class ProgressOverlay(
    private val service: AccessibilityService,
    private val coverScreen: Boolean,
    private val onStop: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)!!
    private var root: View? = null
    private var title: TextView? = null
    private var stepLine: TextView? = null
    private var freedLine: TextView? = null
    private var bar: ProgressBar? = null

    /** The cover catches every tap, so the helper must not tap the screen while it's up. */
    val allowsScreenTaps: Boolean get() = !coverScreen || root == null

    fun show() {
        if (root != null) return
        val view = if (coverScreen) buildCover() else buildBar()
        try {
            windowManager.addView(view, if (coverScreen) coverParams() else barParams())
            root = view
        } catch (e: RuntimeException) {
            // The overlay is a nicety; cleaning works without it.
            title = null
            stepLine = null
            freedLine = null
            bar = null
        }
    }

    fun update(done: Int, total: Int, current: String, clearedBytes: Long) {
        title?.text = if (coverScreen) "${done + 1} of $total · $current" else "Clearing cache ${done + 1} of $total\n$current"
        bar?.progress = done * 100 / total.coerceAtLeast(1)
        freedLine?.text =
            if (clearedBytes > 0) "About ${Formatter.formatShortFileSize(service, clearedBytes)} cleared so far" else ""
    }

    /** The step the helper is on right now, in plain words. */
    fun showStep(text: String) {
        stepLine?.text = text
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        title = null
        stepLine = null
        freedLine = null
        bar = null
    }

    private fun buildCover(): View {
        val dark = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val text = if (dark) Color.rgb(236, 240, 238) else Color.rgb(24, 29, 27)
        val subtle = Color.argb(180, Color.red(text), Color.green(text), Color.blue(text))

        fun label(size: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        val icon = ImageView(service).apply {
            setImageResource(R.drawable.ic_broom)
            setColorFilter(ACCENT)
        }
        val heading = label(24f, text, bold = true).apply { this.text = "Cleaning your apps" }
        val progressText = label(16f, text).apply {
            maxLines = 1
            this.text = "Getting ready…"
        }
        val stepText = label(14f, subtle).apply { maxLines = 3 }
        val progressBar = horizontalBar()
        val freedText = label(14f, subtle)
        val note = label(13f, subtle).apply {
            this.text = "Behind this screen, your phone is opening each app's Settings page so the helper can " +
                "tap “Clear cache”. You can put the phone down: it finishes on its own."
        }
        val stopButton = stopButton()

        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(heading, spaced(top = 16))
            addView(progressText, spaced(top = 8))
            addView(progressBar, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(stepText, spaced(top = 12))
            addView(freedText, spaced(top = 8))
            addView(note, spaced(top = 32))
            addView(stopButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        }
        title = progressText
        stepLine = stepText
        freedLine = freedText
        bar = progressBar

        return FrameLayout(service).apply {
            setBackgroundColor(if (dark) Color.rgb(16, 20, 18) else Color.rgb(244, 248, 246))
            // Swallow taps so they can't reach the Settings page underneath.
            isClickable = true
            isSoundEffectsEnabled = false
            addView(
                column,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
    }

    private fun buildBar(): View {
        val statusText = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 2
            text = "Getting ready…"
        }
        val stepText = TextView(service).apply {
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 13f
            maxLines = 2
        }
        val progressBar = horizontalBar()
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(stepText)
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(BAR_BACKGROUND)
                cornerRadius = dp(18).toFloat()
            }
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            })
        }
        title = statusText
        stepLine = stepText
        bar = progressBar
        return FrameLayout(service).apply {
            setPadding(dp(12), 0, dp(12), 0)
            addView(card)
        }
    }

    private fun horizontalBar() = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(ACCENT)
    }

    private fun stopButton() = Button(service).apply {
        text = "Stop"
        setOnClickListener {
            isEnabled = false
            text = "Stopping…"
            onStop()
        }
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun coverParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.OPAQUE,
    ).apply {
        // Reach under the status and navigation bars too, so no part of Settings shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun barParams() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        y = statusBarHeight() + dp(8)
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
        val BAR_BACKGROUND = Color.argb(240, 32, 33, 36)
        val ACCENT = Color.rgb(52, 211, 153)
    }
}
