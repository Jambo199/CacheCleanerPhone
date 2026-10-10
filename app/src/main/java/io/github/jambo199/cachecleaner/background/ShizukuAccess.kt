package io.github.jambo199.cachecleaner.background

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

enum class ShizukuStatus {
    /** The Shizuku app isn't on the phone. */
    NOT_INSTALLED,

    /** Installed but not started (it needs starting again after every restart). */
    NOT_RUNNING,

    /** Too old for this app. */
    TOO_OLD,

    /** Running, but Cache Cleaner hasn't been allowed to use it yet. */
    NEEDS_PERMISSION,

    READY,
}

/**
 * Shizuku is a separate app that, once started through Wireless debugging, lets other apps run
 * commands with the same rights as a computer plugged in over USB. That lets Cache Cleaner use
 * Android's own `pm trim-caches` command, which clears every app's cache at once in the
 * background, with no Settings pages and no accessibility helper.
 */
object ShizukuAccess {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val PLAY_STORE_PAGE = "https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"
    const val GITHUB_PAGE = "https://github.com/RikkaApps/Shizuku/releases"

    private const val PERMISSION_REQUEST = 4201

    fun status(context: Context): ShizukuStatus {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return if (isInstalled(context)) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return ShizukuStatus.TOO_OLD
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (granted) ShizukuStatus.READY else ShizukuStatus.NEEDS_PERMISSION
    }

    /** Shows Shizuku's "Allow Cache Cleaner?" prompt. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
    }

    /** True if the user said "Deny and don't ask again"; they'd have to allow it inside Shizuku. */
    fun permissionPermanentlyDenied(): Boolean =
        runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    fun openShizuku(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        return open(context, launch)
    }

    fun openWebPage(context: Context, url: String): Boolean = open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /** The outcome of a command run through Shizuku. */
    data class CommandResult(val exitCode: Int, val output: String)

    /**
     * Runs [command] with Shizuku's rights and waits up to [timeoutSeconds] for it. Blocking: call
     * off the main thread. Throws if Shizuku isn't usable.
     */
    fun run(command: Array<String>, timeoutSeconds: Long): CommandResult {
        val process = newProcess.invoke(null, command, null, null) as ShizukuRemoteProcess
        try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val errors = process.errorStream.bufferedReader().use { it.readText() }
            val finished = process.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS)
            return CommandResult(if (finished) process.exitValue() else -1, (output + "\n" + errors).trim())
        } finally {
            process.destroy()
        }
    }

    // Shizuku 13 made newProcess private to steer apps towards its bigger "user service" setup,
    // which is far more than a single command needs. Calling it directly is what most Shizuku
    // apps do; the library version is pinned, so the method can't change underneath us.
    private val newProcess: Method by lazy {
        Shizuku::class.java
            .getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    private fun isInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        false
    }
}
