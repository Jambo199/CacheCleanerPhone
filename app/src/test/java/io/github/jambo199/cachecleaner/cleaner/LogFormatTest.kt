package io.github.jambo199.cachecleaner.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {

    private fun entry(text: String, kind: LogKind = LogKind.STEP, pkg: String? = null, label: String? = null) =
        LogEntry(time = 1_000L, kind = kind, packageName = pkg, appLabel = label, text = text)

    @Test
    fun `groups each app's steps under it`() {
        val sections = LogFormat.group(
            listOf(
                entry("Started cleaning 2 apps"),
                entry("Opening its page in Settings", pkg = "a", label = "App A"),
                entry("Tapped “Clear cache”", pkg = "a", label = "App A"),
                entry("Cache cleared", LogKind.DONE, pkg = "a", label = "App A"),
                entry("Opening its page in Settings", pkg = "b", label = "App B"),
                entry("Nothing to clear", LogKind.SKIPPED, pkg = "b", label = "App B"),
                entry("Went through all 2 apps", LogKind.DONE),
            ),
        )

        assertEquals(4, sections.size)
        assertTrue(sections[0] is LogSection.General)
        val first = sections[1] as LogSection.App
        assertEquals("App A", first.appLabel)
        assertEquals(3, first.steps.size)
        assertEquals(LogKind.DONE, first.result)
        val second = sections[2] as LogSection.App
        assertEquals(LogKind.SKIPPED, second.result)
        assertTrue(sections[3] is LogSection.General)
    }

    @Test
    fun `an app's label falls back to its package name`() {
        val section = LogFormat.group(listOf(entry("Opening", pkg = "com.example"))).single() as LogSection.App
        assertEquals("com.example", section.appLabel)
    }

    @Test
    fun `saved lines read back the same`() {
        val original = listOf(
            entry("Started cleaning 1 app"),
            entry("Tapped “Internal storage”", pkg = "com.whatsapp", label = "WhatsApp"),
            entry("Cache cleared (it was 120 MB)", LogKind.DONE, pkg = "com.whatsapp", label = "WhatsApp"),
        )
        val restored = original.map { LogFormat.decode(LogFormat.encode(it)) }
        assertEquals(original, restored)
    }

    @Test
    fun `tabs and line breaks in text don't break saving`() {
        val restored = LogFormat.decode(LogFormat.encode(entry("two\tparts\nand lines", pkg = "p", label = "L")))
        assertEquals("two parts and lines", restored?.text)
    }

    @Test
    fun `damaged lines are skipped`() {
        assertNull(LogFormat.decode(""))
        assertNull(LogFormat.decode("not a log line"))
        assertNull(LogFormat.decode("abc\tSTEP\t\t\ttext"))
        assertNull(LogFormat.decode("1\tUNKNOWN\t\t\ttext"))
    }
}
