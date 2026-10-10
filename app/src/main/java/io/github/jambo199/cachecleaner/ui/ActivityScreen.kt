package io.github.jambo199.cachecleaner.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jambo199.cachecleaner.cleaner.ActivityLog
import io.github.jambo199.cachecleaner.cleaner.LogEntry
import io.github.jambo199.cachecleaner.cleaner.LogFormat
import io.github.jambo199.cachecleaner.cleaner.LogKind
import io.github.jambo199.cachecleaner.cleaner.LogSection

/**
 * Everything the helper did during the latest clean, in plain words, grouped by app.
 * It updates live while a clean is running.
 */
@Composable
fun ActivityScreen(running: Boolean, onBack: () -> Unit, onClear: () -> Unit) {
    val entries by ActivityLog.entries.collectAsStateWithLifecycle()
    val sections = remember(entries) { LogFormat.group(entries) }
    val listState = rememberLazyListState()

    // Follow along while cleaning: keep the newest step in view.
    LaunchedEffect(entries.size) {
        if (running && sections.isNotEmpty()) listState.animateScrollToItem(sections.size)
    }

    Scaffold { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ActivityHeader(running, onBack) }
            if (sections.isEmpty()) {
                item { EmptyActivity() }
            }
            items(sections) { section ->
                when (section) {
                    is LogSection.General -> GeneralStep(section.entry)
                    is LogSection.App -> AppSteps(section, running)
                }
            }
            if (entries.isNotEmpty() && !running) {
                item {
                    TextButton(onClick = onClear) { Text("Clear this list") }
                }
            }
        }
    }
}

@Composable
private fun ActivityHeader(running: Boolean, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text("What Cache Cleaner did", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            if (running) {
                "Cleaning now. This list fills in as it goes. The bar at the top of your screen shows the same steps."
            } else {
                "Every step from your last clean, in plain words."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(LogKind.DONE, "cleared")
            Legend(LogKind.SKIPPED, "nothing to do")
            Legend(LogKind.PROBLEM, "problem")
        }
    }
}

@Composable
private fun Legend(kind: LogKind, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        KindDot(kind)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyActivity() {
    Card(Modifier.fillMaxWidth()) {
        Text(
            "Nothing here yet. When you tap Clean, every step shows up here: which app's page it " +
                "opened, what it tapped, and whether the cache was cleared.",
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** A step about the whole clean, like "Started cleaning 42 apps". */
@Composable
private fun GeneralStep(entry: LogEntry) {
    val context = LocalContext.current
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        KindDot(entry.kind)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                DateUtils.formatDateTime(context, entry.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One app's steps under its icon and name, with how it ended up. */
@Composable
private fun AppSteps(section: LogSection.App, running: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(section.packageName, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    section.appLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                ResultBadge(section.result, running)
            }
            section.steps.forEach { step ->
                Row(verticalAlignment = Alignment.Top) {
                    KindDot(step.kind)
                    Spacer(Modifier.width(10.dp))
                    Text(step.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ResultBadge(result: LogKind, running: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (label, background, foreground) = when (result) {
        LogKind.DONE -> Triple("Cleared", colors.primaryContainer, colors.onPrimaryContainer)
        LogKind.SKIPPED -> Triple("Nothing to clear", colors.surfaceVariant, colors.onSurfaceVariant)
        LogKind.PROBLEM -> Triple("Not cleared", colors.errorContainer, colors.onErrorContainer)
        LogKind.STEP -> Triple(if (running) "Working…" else "Unfinished", colors.secondaryContainer, colors.onSecondaryContainer)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** ✓ cleared, – nothing to do, ! problem, • a step along the way. */
@Composable
private fun KindDot(kind: LogKind) {
    val colors = MaterialTheme.colorScheme
    val (symbol, background, foreground) = when (kind) {
        LogKind.DONE -> Triple("✓", colors.primary, colors.onPrimary)
        LogKind.SKIPPED -> Triple("–", colors.surfaceVariant, colors.onSurfaceVariant)
        LogKind.PROBLEM -> Triple("!", colors.error, colors.onError)
        LogKind.STEP -> Triple("•", Color.Transparent, colors.onSurfaceVariant)
    }
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
