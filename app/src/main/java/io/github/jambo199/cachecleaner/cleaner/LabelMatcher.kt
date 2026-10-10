package io.github.jambo199.cachecleaner.cleaner

/**
 * Decides whether a piece of on-screen text is the button or row we are looking for.
 *
 * Kept free of Android types so the safety rules can be unit tested.
 */
object LabelMatcher {

    private val whitespace = Regex("\\s+")

    fun normalize(text: CharSequence): String =
        text.toString().replace(' ', ' ').replace(whitespace, " ").trim().lowercase()

    /**
     * True when [text] is exactly [label], or starts with it as its own line or comma-separated
     * part. The second case covers rows that read "Storage & cache, 1.2 GB used" as one piece of
     * text, while still rejecting look-alikes such as "Clear storage" or "Clear cache?".
     */
    fun matches(text: CharSequence?, label: String): Boolean {
        if (text.isNullOrBlank()) return false
        val wanted = normalize(label)
        if (wanted.isEmpty()) return false
        if (normalize(text) == wanted) return true
        return normalize(text.split('\n', ',').first()) == wanted
    }

    fun matchesAny(text: CharSequence?, labels: List<String>): Boolean =
        labels.any { matches(text, it) }

    /**
     * A confirmation dialog may only be accepted when it talks about the cache and says nothing
     * about deleting app data, so we can never confirm a "Delete app data?" prompt by mistake.
     */
    fun isCacheOnlyDialog(windowText: String, cacheWords: List<String>, dangerWords: List<String>): Boolean {
        val text = normalize(windowText)
        fun mentions(words: List<String>) = words.any { word ->
            val w = normalize(word)
            w.isNotEmpty() && w in text
        }
        return mentions(cacheWords) && !mentions(dangerWords)
    }

    /** Removes blanks and duplicates (ignoring case and spacing), keeping the first spelling. */
    fun distinctLabels(labels: List<String>): List<String> =
        labels.filter { it.isNotBlank() }.distinctBy { normalize(it) }
}
