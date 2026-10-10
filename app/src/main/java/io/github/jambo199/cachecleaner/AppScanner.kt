package io.github.jambo199.cachecleaner

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.text.format.Formatter
import io.github.jambo199.cachecleaner.cleaner.AppCacheInfo

object AppScanner {

    /** Smaller caches are just empty folders, not worth a trip into Settings. */
    const val MIN_CACHE_BYTES = 16L * 1024

    /**
     * The apps worth cleaning, biggest cache first. With usage access that is every app holding
     * some cache; without it we can't see sizes, so it is every app the user installed or updated.
     */
    fun scan(context: Context): List<AppCacheInfo> {
        val pm = context.packageManager
        val withSizes = Permissions.hasUsageAccess(context)
        val stats = context.getSystemService(StorageStatsManager::class.java)

        return installedApps(pm)
            .asSequence()
            .filter { it.enabled && it.packageName != context.packageName }
            .mapNotNull { info ->
                val cache = if (withSizes) cacheBytes(stats, info) else AppCacheInfo.UNKNOWN
                val preinstalled = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                when {
                    withSizes && cache < MIN_CACHE_BYTES -> null
                    !withSizes && preinstalled -> null
                    else -> AppCacheInfo(info.packageName, info.loadLabel(pm).toString(), cache)
                }
            }
            .sortedWith(compareByDescending<AppCacheInfo> { it.cacheBytes }.thenBy { it.label.lowercase() })
            .toList()
    }

    /** Current cache sizes for [packages], or null without usage access. */
    fun cacheSizes(context: Context, packages: Collection<String>): Map<String, Long>? {
        if (!Permissions.hasUsageAccess(context)) return null
        val pm = context.packageManager
        val stats = context.getSystemService(StorageStatsManager::class.java)
        return packages.associateWith { pkg ->
            val info = try {
                applicationInfo(pm, pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                return@associateWith 0L // Uninstalled meanwhile: nothing left.
            }
            cacheBytes(stats, info)
        }
    }

    /**
     * The app's total size the way Settings prints it on its App info page ("231 MB"), or nothing
     * without usage access.
     */
    fun sizeTexts(context: Context, packageName: String): List<String> {
        if (!Permissions.hasUsageAccess(context)) return emptyList()
        val stats = context.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        return try {
            val info = applicationInfo(context.packageManager, packageName)
            val app = stats.queryStatsForPackage(info.storageUuid, packageName, Process.myUserHandle())
            val total = app.appBytes + app.dataBytes
            listOf(Formatter.formatFileSize(context, total), Formatter.formatShortFileSize(context, total)).distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun cacheBytes(stats: StorageStatsManager?, info: ApplicationInfo): Long = try {
        stats?.queryStatsForPackage(info.storageUuid, info.packageName, Process.myUserHandle())?.cacheBytes
            ?: AppCacheInfo.UNKNOWN
    } catch (e: Exception) {
        // SecurityException without usage access, NameNotFoundException, IOException...
        AppCacheInfo.UNKNOWN
    }

    private fun installedApps(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

    private fun applicationInfo(pm: PackageManager, pkg: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
}
