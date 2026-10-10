package io.github.jambo199.cachecleaner.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelMatcherTest {

    private val cacheWords = listOf("Clear cache", "cach")
    private val danger = listOf("Clear data", "Clear storage", "Delete app data", "app data", "all data")

    @Test
    fun `matches exact labels ignoring case and spacing`() {
        assertTrue(LabelMatcher.matches("Clear cache", "Clear cache"))
        assertTrue(LabelMatcher.matches("CLEAR  CACHE", "clear cache"))
        assertTrue(LabelMatcher.matches("Clear cache ", "Clear cache"))
    }

    @Test
    fun `matches a row whose title is followed by its summary`() {
        assertTrue(LabelMatcher.matches("Storage & cache, 1.2 GB used in internal storage", "Storage & cache"))
        assertTrue(LabelMatcher.matches("Storage\n231 MB used in Internal storage", "Storage"))
    }

    @Test
    fun `never mistakes data-wiping buttons for clear cache`() {
        assertFalse(LabelMatcher.matches("Clear storage", "Clear cache"))
        assertFalse(LabelMatcher.matches("Clear data", "Clear cache"))
        assertFalse(LabelMatcher.matches("Clear cache?", "Clear cache"))
        assertFalse(LabelMatcher.matches("Clear all data", "Clear cache"))
    }

    @Test
    fun `does not match summaries that merely contain the label`() {
        assertFalse(LabelMatcher.matches("231 MB used in internal storage", "Storage"))
        assertFalse(LabelMatcher.matches(null, "Storage"))
        assertFalse(LabelMatcher.matches("", "Storage"))
        assertFalse(LabelMatcher.matches("Storage", " "))
    }

    @Test
    fun `finds a storage row by the app size it shows`() {
        val sizes = listOf("231 MB")
        assertTrue(LabelMatcher.mentionsSize("231 MB used in internal storage", sizes))
        assertTrue(LabelMatcher.mentionsSize("Internal storage, 231 MB used", sizes))
        assertTrue(LabelMatcher.mentionsSize("Total: 231 MB", sizes))
        assertFalse(LabelMatcher.mentionsSize("1,231 MB used", sizes))
        assertFalse(LabelMatcher.mentionsSize("1231 MB used", sizes))
        assertFalse(LabelMatcher.mentionsSize("231 MBit/s", sizes))
        assertFalse(LabelMatcher.mentionsSize("Notifications", sizes))
        assertFalse(LabelMatcher.mentionsSize(null, sizes))
    }

    @Test
    fun `accepts a clear cache confirmation`() {
        assertTrue(LabelMatcher.isCacheOnlyDialog("Clear cache?\nCancel\nOK", cacheWords, danger))
        assertTrue(LabelMatcher.isCacheOnlyDialog("Vider le cache ?\nAnnuler\nOK", cacheWords, danger))
    }

    @Test
    fun `refuses a delete app data confirmation`() {
        assertFalse(
            LabelMatcher.isCacheOnlyDialog(
                "Delete app data?\nAll this app's data will be deleted permanently.\nCancel\nOK",
                cacheWords,
                danger,
            ),
        )
        assertFalse(LabelMatcher.isCacheOnlyDialog("Clear all data\nClear cache\nCancel", cacheWords, danger))
        assertFalse(LabelMatcher.isCacheOnlyDialog("Are you sure?\nCancel\nOK", cacheWords, danger))
    }

    @Test
    fun `distinct labels drops blanks and case duplicates`() {
        assertEquals(
            listOf("Clear cache", "Storage"),
            LabelMatcher.distinctLabels(listOf("Clear cache", "clear  cache", "", "Storage", " ")),
        )
    }
}
