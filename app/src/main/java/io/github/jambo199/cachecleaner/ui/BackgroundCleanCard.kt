package io.github.jambo199.cachecleaner.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jambo199.cachecleaner.background.BackgroundCleanState
import io.github.jambo199.cachecleaner.background.ShizukuAccess
import io.github.jambo199.cachecleaner.background.ShizukuStatus

/** Cleaning every app in the background through Shizuku, once it's set up. */
@Composable
fun BackgroundCleanCard(
    background: BackgroundCleanState,
    cacheText: String?,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    onShowActivity: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (background) {
                BackgroundCleanState.Running -> {
                    Text("Cleaning in the background…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "This takes a few seconds. You can leave the app; a notification tells you when it's done.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is BackgroundCleanState.Done -> {
                    Text("All done", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Freed ${Formatter.formatShortFileSize(context, background.freedBytes)}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onShowActivity) { Text("See every step") }
                        TextButton(onClick = onDismiss) { Text("OK") }
                    }
                }
                is BackgroundCleanState.Failed -> {
                    Text("Background clean didn't finish", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(background.message, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart) { Text("Try again") }
                        TextButton(onClick = onDismiss) { Text("OK") }
                    }
                }
                BackgroundCleanState.Idle -> {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(
                            if (cacheText != null) "Clean all apps in the background · $cacheText" else "Clean all apps in the background",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        "Clears every app's cache in a few seconds using Shizuku. Nothing opens on screen, so " +
                            "you can keep using your phone; a notification shows when it's done.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** The next step towards background cleaning, depending on where Shizuku is at. */
@Composable
fun ShizukuSetup(status: ShizukuStatus, onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            ShizukuStatus.NOT_INSTALLED -> {
                Text(
                    "Install Shizuku, a free app that lets Cache Cleaner use Android's own “clear all caches” " +
                        "command in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ShizukuAccess.openWebPage(context, ShizukuAccess.PLAY_STORE_PAGE) }) { Text("Get Shizuku") }
                    TextButton(onClick = { ShizukuAccess.openWebPage(context, ShizukuAccess.GITHUB_PAGE) }) { Text("No Play Store?") }
                }
            }
            ShizukuStatus.NOT_RUNNING -> {
                Text(
                    "Shizuku is installed but not started. Open it and choose “Start via Wireless debugging”; it " +
                        "walks you through it (you'll need Wi-Fi and Developer options turned on). After restarting " +
                        "your phone, open Shizuku and start it again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { ShizukuAccess.openShizuku(context) }) { Text("Open Shizuku") }
            }
            ShizukuStatus.TOO_OLD -> {
                Text("Your Shizuku is too old. Update it, then come back.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { ShizukuAccess.openWebPage(context, ShizukuAccess.PLAY_STORE_PAGE) }) { Text("Update Shizuku") }
            }
            ShizukuStatus.NEEDS_PERMISSION -> {
                Text(
                    if (ShizukuAccess.permissionPermanentlyDenied()) {
                        "Cache Cleaner was blocked in Shizuku. Open Shizuku, find Cache Cleaner in its list of apps, and allow it."
                    } else {
                        "Shizuku is running. Allow Cache Cleaner to use it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAllow) { Text("Allow") }
                    TextButton(onClick = { ShizukuAccess.openShizuku(context) }) { Text("Open Shizuku") }
                }
            }
            ShizukuStatus.READY -> Text(
                "✓ Ready. Background cleaning clears every app's cache in a few seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Shown on the main screen until Shizuku is ready. */
@Composable
fun ShizukuSetupCard(status: ShizukuStatus, onAllow: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OPTIONAL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Clean in the background", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ShizukuSetup(status, onAllow)
            Text(
                "Until then, the Clean button above works through the helper instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
