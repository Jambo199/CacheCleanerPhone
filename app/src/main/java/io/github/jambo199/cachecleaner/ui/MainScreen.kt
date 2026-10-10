package io.github.jambo199.cachecleaner.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import android.text.format.Formatter
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jambo199.cachecleaner.MainViewModel
import io.github.jambo199.cachecleaner.Permissions
import io.github.jambo199.cachecleaner.R
import io.github.jambo199.cachecleaner.RunSummary
import io.github.jambo199.cachecleaner.UiState
import io.github.jambo199.cachecleaner.cleaner.ActivityLog
import io.github.jambo199.cachecleaner.cleaner.AppCacheInfo
import io.github.jambo199.cachecleaner.cleaner.CleanState
import io.github.jambo199.cachecleaner.cleaner.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_FAILED_SHOWN = 8
private const val ICON_PX = 120

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    val quickClean = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        message(if (result.resultCode == Activity.RESULT_OK) "Shared cache folders cleared." else "Quick clean didn't run.")
        viewModel.scan()
    }
    val log by ActivityLog.entries.collectAsStateWithLifecycle()
    var showActivity by rememberSaveable { mutableStateOf(false) }

    if (showActivity) {
        BackHandler { showActivity = false }
        ActivityScreen(
            running = state.cleaning != null,
            onBack = { showActivity = false },
            onClear = viewModel::clearActivity,
        )
        return
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(state) }

            state.cleaning?.let { running ->
                item {
                    RunningCard(
                        running = running,
                        latestStep = log.lastOrNull()?.text,
                        onStop = viewModel::stopCleaning,
                        onShowActivity = { showActivity = true },
                    )
                }
            }
            state.lastRun?.let { run ->
                item {
                    ResultCard(
                        run = run,
                        onOpenApp = { Permissions.openAppInfo(context, it) },
                        onShowActivity = { showActivity = true },
                        onDismiss = viewModel::dismissResult,
                    )
                }
            }
            if (!state.hasUsageAccess) {
                item { UsageAccessCard(onAllow = { Permissions.openUsageAccess(context) }) }
            }
            if (!state.helperRunning) {
                item {
                    HelperCard(
                        switchedOn = state.helperSwitchedOn,
                        onOpenAccessibility = { Permissions.openAccessibility(context) },
                        onOpenAppInfo = { Permissions.openAppInfo(context, context.packageName) },
                    )
                }
            }
            if (state.cleaning == null) {
                item {
                    CleanButton(state) {
                        if (!viewModel.startCleaning()) message("Turn on the auto-clean helper first.")
                    }
                }
            }
            if (log.isNotEmpty() && state.cleaning == null && state.lastRun == null) {
                item {
                    OutlinedButton(onClick = { showActivity = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("See what it did last time")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                item {
                    QuickCleanCard(
                        hasAccess = state.hasAllFilesAccess,
                        onGrant = { Permissions.openAllFilesAccess(context) },
                        onRun = {
                            try {
                                quickClean.launch(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
                            } catch (e: ActivityNotFoundException) {
                                message("This phone doesn't offer a quick clean.")
                            } catch (e: SecurityException) {
                                message("Android refused the quick clean. Check “All files access” is on.")
                            }
                        },
                    )
                }
            }

            item { ListHeader(state, onSelectAll = viewModel::selectAll, onRescan = viewModel::scan) }
            items(state.apps, key = { it.packageName }) { app ->
                AppRow(app, checked = app.packageName in state.selected, onToggle = { viewModel.toggle(app.packageName) })
            }
        }
    }
}

@Composable
private fun Header(state: UiState) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_broom),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("Cache Cleaner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
        when {
            !state.scanned -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Looking through your apps…")
            }
            state.hasUsageAccess -> {
                Text(
                    formatSize(context, state.totalCacheBytes),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (state.apps.isEmpty()) "Your apps have no cache to clear." else "of cache in ${plural(state.apps.size, "app")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Text(
                "${plural(state.apps.size, "app")} can be cleaned. Allow usage access to see how much cache each one is holding.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunningCard(
    running: CleanState.Running,
    latestStep: String?,
    onStop: () -> Unit,
    onShowActivity: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Cleaning… ${running.done} of ${running.total} done",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(running.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
            latestStep?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LinearProgressIndicator(
                progress = { running.done / running.total.coerceAtLeast(1).toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
                TextButton(onClick = onShowActivity) { Text("See every step") }
            }
        }
    }
}

@Composable
private fun ResultCard(
    run: RunSummary,
    onOpenApp: (String) -> Unit,
    onShowActivity: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = when {
                run.blocked -> "Your phone stopped the helper"
                run.unsupported -> "Couldn't find the “Clear cache” button"
                run.stoppedEarly -> "Stopped"
                else -> "All done"
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            run.freedBytes?.let {
                Text("Freed ${formatSize(context, it)}", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                buildString {
                    append("Cleared ${plural(run.cleared, "app")}")
                    if (run.alreadyEmpty > 0) append(" · ${run.alreadyEmpty} already empty")
                },
            )
            if (run.blocked) {
                Text(blockedAdvice(), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { onOpenApp(context.packageName) }) { Text("Open Cache Cleaner's App info") }
            }
            if (run.unsupported) {
                Text(
                    "This phone's Settings screens look different from the ones Cache Cleaner knows. " +
                        "You can still clear an app by hand: tap Open, go to its storage page, then tap Clear cache.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (run.failed.isNotEmpty()) {
                Text("Couldn't clear automatically:", style = MaterialTheme.typography.labelLarge)
                run.failed.take(MAX_FAILED_SHOWN).forEach { app ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onOpenApp(app.packageName) }) { Text("Open") }
                    }
                }
                if (run.failed.size > MAX_FAILED_SHOWN) {
                    Text("…and ${run.failed.size - MAX_FAILED_SHOWN} more", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onShowActivity) { Text("See every step") }
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

private fun blockedAdvice(): String = when {
    Phone.isVivo ->
        "vivo phones don't let apps open screens from the background until you allow it. Open " +
            "Cache Cleaner's App info, tap Permissions, turn on “Background pop-ups” (it can be " +
            "under “All permissions” or “Other permissions”), then tap Clean again."
    Phone.isXiaomi ->
        "Xiaomi phones don't let apps open screens from the background until you allow it. Open " +
            "Cache Cleaner's App info, tap Other permissions, allow “Display pop-up windows while " +
            "running in the background”, then tap Clean again."
    else ->
        "Cache Cleaner wasn't allowed to open the next app's Settings page. Look in its App info " +
            "for a permission about pop-ups or opening screens in the background, allow it, then " +
            "tap Clean again."
}

@Composable
private fun UsageAccessCard(onAllow: () -> Unit) {
    SetupCard(
        step = "Step 1",
        title = "Allow usage access",
        body = "This lets Cache Cleaner see how much cache each app is holding, so it only visits apps " +
            "that have something to clear. Find Cache Cleaner in the list and switch it on.",
        action = "Allow usage access",
        onAction = onAllow,
    )
}

@Composable
private fun HelperCard(switchedOn: Boolean, onOpenAccessibility: () -> Unit, onOpenAppInfo: () -> Unit) {
    SetupCard(
        step = "Step 2",
        title = "Turn on the auto-clean helper",
        body = "Android only lets an app's cache be cleared from that app's page in Settings. The helper " +
            "opens each page and taps “Clear cache” for you, just like you would by hand. It only acts " +
            "while a clean is running and never taps “Clear data”.\n\n" +
            "Open Accessibility, pick Cache Cleaner (it may be under “Installed apps” or “Downloaded " +
            "apps”) and switch it on.",
        action = "Open Accessibility settings",
        onAction = onOpenAccessibility,
    ) {
        if (switchedOn) {
            Text(
                "It's switched on but isn't running. Switch it off and on again.",
                color = MaterialTheme.colorScheme.error,
            )
            if (Phone.isVivo) {
                Text(
                    "vivo can switch helpers off to save battery. To stop that, allow Cache Cleaner " +
                        "high background power use in Settings → Battery.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "Switch greyed out, or a “Restricted setting” message? Android 13 and newer block this for " +
                "apps installed outside the Play Store until you allow it: open Cache Cleaner's App info, " +
                "tap ⋮ in the top corner, choose “Allow restricted settings”, then try again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenAppInfo) { Text("Open Cache Cleaner's App info") }
    }
}

@Composable
private fun SetupCard(
    step: String,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(step.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction) { Text(action) }
            extra()
        }
    }
}

@Composable
private fun CleanButton(state: UiState, onClean: () -> Unit) {
    val context = LocalContext.current
    val count = state.selectedApps.size
    val canClean = state.helperRunning && count > 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onClean,
            enabled = canClean,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            val label = when {
                !state.helperRunning -> "Turn on the helper to clean"
                count == 0 -> "Nothing selected"
                state.hasUsageAccess -> "Clean ${plural(count, "app")} · ${formatSize(context, state.selectedCacheBytes)}"
                else -> "Clean ${plural(count, "app")}"
            }
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        if (canClean) {
            Text(
                "Your phone will flip through each app's Settings page — that's the helper at work. " +
                    "Tap Stop at the top of the screen to cancel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickCleanCard(hasAccess: Boolean, onGrant: () -> Unit, onRun: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Quick clean (no helper needed)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Android can empty every app's shared cache folder in one go. It can't reach the apps' " +
                    "private caches, so it frees less than a full clean. It needs “All files access”.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (hasAccess) {
                FilledTonalButton(onClick = onRun) { Text("Run quick clean") }
            } else {
                OutlinedButton(onClick = onGrant) { Text("Allow all files access") }
            }
        }
    }
}

@Composable
private fun ListHeader(state: UiState, onSelectAll: (Boolean) -> Unit, onRescan: () -> Unit) {
    val allSelected = state.apps.isNotEmpty() && state.selected.size == state.apps.size
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (state.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        TextButton(onClick = onRescan, enabled = !state.scanning && state.cleaning == null) { Text("Rescan") }
        TextButton(onClick = { onSelectAll(!allSelected) }, enabled = state.apps.isNotEmpty()) {
            Text(if (allSelected) "Select none" else "Select all")
        }
    }
}

@Composable
private fun AppRow(app: AppCacheInfo, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (app.cacheBytes >= 0) formatSize(context, app.cacheBytes) else app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
internal fun AppIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val icon by produceState(initialValue = IconCache.peek(packageName), packageName) {
        if (value == null) value = withContext(Dispatchers.IO) { IconCache.load(context, packageName) }
    }
    val modifier = Modifier.size(size)
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private object IconCache {
    private val cache = LruCache<String, ImageBitmap>(256)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    fun load(context: Context, packageName: String): ImageBitmap? = cache.get(packageName)
        ?: runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }
            .getOrNull()
            ?.also { cache.put(packageName, it) }
}

private fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

private fun plural(count: Int, word: String) = if (count == 1) "1 $word" else "$count ${word}s"
