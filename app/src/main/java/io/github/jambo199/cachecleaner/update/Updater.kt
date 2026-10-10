package io.github.jambo199.cachecleaner.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Updates the app from the "latest" release that GitHub Actions publishes.
 *
 * It never installs anything by itself: it downloads the new APK, checks that it really is a newer
 * Cache Cleaner signed with the same key, and hands it to Android's installer, which asks the user
 * to confirm. The only thing it sends anywhere is a plain download request to GitHub.
 */
object Updater {

    private const val RELEASE_FILES = "https://github.com/Jambo199/CacheCleanerPhone/releases/download/latest/"
    const val DOWNLOAD_PAGE = "https://github.com/Jambo199/CacheCleanerPhone/releases/latest"
    const val PROJECT_PAGE = "https://github.com/Jambo199/CacheCleanerPhone"

    private const val TIMEOUT_MS = 15_000
    private const val APK_NAME = "CacheCleaner.apk"

    sealed interface CheckResult {
        data class Found(val manifest: UpdateManifest) : CheckResult

        /** [needsSignIn]: GitHub only shows the files to signed-in people, e.g. a private project. */
        data class Failed(val reason: String, val needsSignIn: Boolean) : CheckResult
    }

    fun installedVersionCode(context: Context): Long = PackageInfoCompat.getLongVersionCode(packageInfo(context, 0))

    fun installedVersionName(context: Context): String = packageInfo(context, 0).versionName ?: "?"

    /** Asks GitHub which version is newest. Blocking: call off the main thread. */
    fun check(): CheckResult = try {
        val connection = connect(RELEASE_FILES + "version.json")
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val manifest = connection.inputStream.bufferedReader().use { UpdateManifest.parse(it.readText()) }
                    if (manifest != null) {
                        CheckResult.Found(manifest)
                    } else {
                        CheckResult.Failed("GitHub sent something that isn't update information.", needsSignIn = false)
                    }
                }
                HttpURLConnection.HTTP_NOT_FOUND -> CheckResult.Failed(
                    "Couldn't see the latest version on GitHub. If the project is private, only a browser " +
                        "where you're signed in to GitHub can open its downloads.",
                    needsSignIn = true,
                )
                else -> CheckResult.Failed("GitHub answered with an error ($code). Try again later.", needsSignIn = false)
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        CheckResult.Failed("Couldn't reach GitHub. Check your internet connection and try again.", needsSignIn = false)
    }

    /** Downloads the newest APK into the app's private storage. Blocking: call off the main thread. */
    @Throws(IOException::class)
    fun download(context: Context, onProgress: (Float) -> Unit): File {
        val folder = File(context.filesDir, "updates").apply { mkdirs() }
        val partial = File(folder, "$APK_NAME.part")
        val target = File(folder, APK_NAME)
        val connection = connect(RELEASE_FILES + APK_NAME)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        target.delete()
        if (!partial.renameTo(target)) throw IOException("Couldn't save the download")
        return target
    }

    /**
     * Makes sure [apk] is a newer Cache Cleaner signed with the same key as the installed one.
     * Returns what's wrong in plain words, or null if it's fine to install.
     */
    fun verify(context: Context, apk: File): String? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val download = archiveInfo(context.packageManager, apk, flags)
            ?: return "The download is damaged. Try again."
        if (download.packageName != context.packageName) return "The download isn't Cache Cleaner, so it wasn't installed."
        val installed = packageInfo(context, flags)
        if (PackageInfoCompat.getLongVersionCode(download) <= PackageInfoCompat.getLongVersionCode(installed)) {
            return "You already have this version."
        }
        // Android refuses updates signed with a different key anyway; checking first gives a clear message.
        val downloadSigners = signers(download)
        val installedSigners = signers(installed)
        if (downloadSigners.isNotEmpty() && installedSigners.isNotEmpty() && downloadSigners != installedSigners) {
            return "The download isn't signed the same way as your app, so it wasn't installed."
        }
        return null
    }

    /** Android needs the user's OK ("Install unknown apps") before an app may offer updates. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        open(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null)))
    }

    /** Shows Android's "Do you want to update this app?" screen for [apk]. */
    fun install(context: Context, apk: File): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return open(context, intent)
    }

    fun openWebPage(context: Context, url: String): Boolean = open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        false
    }

    private fun connect(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CacheCleaner-updater")
        }

    private fun signers(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        }

    private fun packageInfo(context: Context, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        }

    private fun archiveInfo(pm: PackageManager, apk: File, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, flags)
        }
}
