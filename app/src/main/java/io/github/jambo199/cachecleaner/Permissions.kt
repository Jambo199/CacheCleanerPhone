package io.github.jambo199.cachecleaner

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import io.github.jambo199.cachecleaner.cleaner.CleanerAccessibilityService

object Permissions {

    /** "Usage access" is what lets us read other apps' cache sizes. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Whether the user has switched the helper on in Accessibility settings (it may still be starting). */
    fun isHelperEnabled(context: Context): Boolean {
        val ours = ComponentName(context, CleanerAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun openUsageAccess(context: Context) = openFirst(
        context,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri(context.packageName)),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    fun openAccessibility(context: Context) = openFirst(
        context,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )

    fun openAppInfo(context: Context, packageName: String) = openFirst(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(packageName)),
    )

    fun openAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        openFirst(
            context,
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context.packageName)),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
    }

    private fun packageUri(packageName: String): Uri = Uri.fromParts("package", packageName, null)

    /** Opens the first screen this phone has; Settings screens differ between phone makers. */
    private fun openFirst(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                // Try the next one.
            } catch (e: SecurityException) {
                // Try the next one.
            }
        }
        return false
    }
}
