package io.github.jambo199.cachecleaner.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jambo199.cachecleaner.MainViewModel
import io.github.jambo199.cachecleaner.Permissions
import io.github.jambo199.cachecleaner.SortOrder
import io.github.jambo199.cachecleaner.UiState
import io.github.jambo199.cachecleaner.UpdateState
import io.github.jambo199.cachecleaner.update.Updater

@Composable
fun SettingsScreen(state: UiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                    Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }

            item {
                Section("Cleaning") {
                    SwitchRow(
                        title = "Hide Settings while cleaning",
                        description = if (state.coverScreen) {
                            "A progress screen covers your phone while the helper works behind it."
                        } else {
                            "You'll see your phone flip through each app's Settings page as the helper works."
                        },
                        checked = state.coverScreen,
                        onChange = viewModel::setCoverScreen,
                    )
                    Text("Sort the app list by", style = MaterialTheme.typography.bodyLarge)
                    SortOrder.entries.forEach { order ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setSort(order) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.sort == order, onClick = { viewModel.setSort(order) })
                            Text(order.label)
                        }
                    }
                }
            }

            item {
                Section("Updates") {
                    SwitchRow(
                        title = "Check for updates automatically",
                        description = "When you open the app, it asks GitHub (at most every few hours) " +
                            "whether there's a newer version. Nothing about you or your phone is sent.",
                        checked = state.autoUpdate,
                        onChange = viewModel::setAutoUpdate,
                    )
                    Text(
                        "You have version ${state.versionName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    UpdateStatus(
                        update = state.update,
                        onCheck = { viewModel.checkForUpdates(manual = true) },
                        onInstall = viewModel::installUpdate,
                        onOpenDownloadPage = { Updater.openWebPage(context, Updater.DOWNLOAD_PAGE) },
                    )
                }
            }

            item {
                Section("Permissions") {
                    PermissionRow("Usage access", "Lets it see how much cache each app has.", state.hasUsageAccess) {
                        Permissions.openUsageAccess(context)
                    }
                    PermissionRow("Auto-clean helper", "Taps “Clear cache” for you.", state.helperRunning) {
                        Permissions.openAccessibility(context)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        PermissionRow("All files access", "Only needed for Quick clean.", state.hasAllFilesAccess) {
                            Permissions.openAllFilesAccess(context)
                        }
                    }
                    PermissionRow("Install updates", "Lets it hand updates to Android's installer.", state.canInstallUpdates) {
                        Updater.openInstallPermission(context)
                    }
                    TextButton(onClick = { Permissions.openAppInfo(context, context.packageName) }) {
                        Text("Open Cache Cleaner's App info")
                    }
                }
            }

            item {
                Section("Activity") {
                    Text(
                        "The activity screen keeps every step from your last clean.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = viewModel::clearActivity) { Text("Clear the activity list") }
                }
            }

            item {
                Section("About") {
                    Text(
                        "Cache Cleaner clears app caches by pressing “Clear cache” on each app's Settings " +
                            "page for you. It never deletes app data, logins or files.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { Updater.openWebPage(context, Updater.PROJECT_PAGE) }) {
                        Text("Open the project on GitHub")
                    }
                }
            }
        }
    }
}

/** What the updater is doing, and the buttons that go with it. */
@Composable
internal fun UpdateStatus(
    update: UpdateState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onOpenDownloadPage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (update) {
            UpdateState.Idle -> OutlinedButton(onClick = onCheck) { Text("Check for updates now") }
            UpdateState.Checking -> Text("Checking for updates…")
            UpdateState.UpToDate -> {
                Text("You have the latest version.")
                OutlinedButton(onClick = onCheck) { Text("Check again") }
            }
            is UpdateState.Available -> {
                Text("Version ${update.manifest.versionName} is ready.", fontWeight = FontWeight.SemiBold)
                update.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = onInstall) { Text("Update now") }
            }
            is UpdateState.Downloading -> {
                Text("Downloading version ${update.manifest.versionName}…")
                LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.Failed -> {
                Text(update.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheck) { Text("Try again") }
                    if (update.offerDownloadPage) {
                        TextButton(onClick = onOpenDownloadPage) { Text("Open download page") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(title: String, description: String, granted: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                (if (granted) "✓ Allowed. " else "Not allowed yet. ") + description,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpen) { Text(if (granted) "Change" else "Allow") }
    }
}
