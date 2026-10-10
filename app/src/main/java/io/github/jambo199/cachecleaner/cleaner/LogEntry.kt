package io.github.jambo199.cachecleaner.cleaner

/** How a step turned out, which decides its icon on the activity screen. */
enum class LogKind {
    /** Something the helper did or noticed along the way. */
    STEP,

    /** The app's cache was cleared. */
    DONE,

    /** Nothing needed doing. */
    SKIPPED,

    /** Something went wrong or was stopped. */
    PROBLEM,
}

/** One plain-language line on the activity screen. */
data class LogEntry(
    val time: Long,
    val kind: LogKind,
    /** The app the step was about, or null for steps about the whole clean. */
    val packageName: String?,
    val appLabel: String?,
    val text: String,
)

/** The activity screen shows each app's steps together under its name. */
sealed interface LogSection {
    data class General(val entry: LogEntry) : LogSection

    data class App(val packageName: String, val appLabel: String, val steps: List<LogEntry>) : LogSection {
        /** The app's last step says how it ended up. */
        val result: LogKind get() = steps.last().kind
    }
}

/** Kept free of Android types so it can be unit tested. */
object LogFormat {

    /** Groups back-to-back steps about the same app into one section. */
    fun group(entries: List<LogEntry>): List<LogSection> {
        val sections = ArrayList<LogSection>()
        for (entry in entries) {
            val pkg = entry.packageName
            val last = sections.lastOrNull()
            when {
                pkg == null -> sections += LogSection.General(entry)
                last is LogSection.App && last.packageName == pkg ->
                    sections[sections.lastIndex] = last.copy(steps = last.steps + entry)
                else -> sections += LogSection.App(pkg, entry.appLabel ?: pkg, listOf(entry))
            }
        }
        return sections
    }

    /** One line per entry, tab-separated, for saving the log between app launches. */
    fun encode(entry: LogEntry): String =
        listOf(entry.time.toString(), entry.kind.name, entry.packageName.orEmpty(), entry.appLabel.orEmpty(), entry.text)
            .joinToString("\t") { it.replace(Regex("[\t\r\n]"), " ") }

    fun decode(line: String): LogEntry? {
        val parts = line.split('\t')
        if (parts.size != 5) return null
        val time = parts[0].toLongOrNull() ?: return null
        val kind = LogKind.entries.firstOrNull { it.name == parts[1] } ?: return null
        return LogEntry(time, kind, parts[2].ifEmpty { null }, parts[3].ifEmpty { null }, parts[4])
    }
}
