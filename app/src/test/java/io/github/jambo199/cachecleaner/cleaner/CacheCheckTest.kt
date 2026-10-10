package io.github.jambo199.cachecleaner.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheCheckTest {

    private val mb = 1024L * 1024

    @Test
    fun `a cache that barely shrank counts as still holding`() {
        assertTrue(CacheCheck.isStillHolding(beforeBytes = 120 * mb, afterBytes = 95 * mb))
        assertTrue(CacheCheck.isStillHolding(beforeBytes = 120 * mb, afterBytes = 140 * mb))
    }

    @Test
    fun `a cache that mostly went away counts as cleared`() {
        assertFalse(CacheCheck.isStillHolding(beforeBytes = 120 * mb, afterBytes = 2 * mb))
        assertFalse(CacheCheck.isStillHolding(beforeBytes = 120 * mb, afterBytes = 0))
    }

    @Test
    fun `small leftovers don't count`() {
        assertFalse(CacheCheck.isStillHolding(beforeBytes = 900 * 1024, afterBytes = 800 * 1024))
    }

    @Test
    fun `unknown sizes don't count`() {
        assertFalse(CacheCheck.isStillHolding(beforeBytes = -1, afterBytes = 50 * mb))
        assertFalse(CacheCheck.isStillHolding(beforeBytes = 0, afterBytes = 50 * mb))
    }

    @Test
    fun `lists the apps still holding cache, biggest first`() {
        val before = mapOf("game" to 120 * mb, "chat" to 300 * mb, "browser" to 80 * mb, "gone" to 10 * mb)
        val after = mapOf("game" to 95 * mb, "chat" to 280 * mb, "browser" to 1 * mb)
        assertEquals(listOf("chat", "game"), CacheCheck.stillHolding(before, after))
    }
}
