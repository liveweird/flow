package ch.nokillswit.gitlab

import ch.nokillswit.infra.json.replaceNul

private const val ONE_BYTE_LIMIT = 0x80
private const val TWO_BYTE_LIMIT = 0x800
private const val THREE_BYTE_LIMIT = 0x10000

/** The UTF-8 size of one code point. */
private fun utf8Size(codePoint: Int): Int = when {
    codePoint < ONE_BYTE_LIMIT -> 1
    codePoint < TWO_BYTE_LIMIT -> 2
    codePoint < THREE_BYTE_LIMIT -> 3
    else -> 4
}

/**
 * Stored upstream text: [text] with NUL replaced (PostgreSQL refuses `\u0000` in text), cut to at most [maxChars] characters (code points,
 * which is what a `VARCHAR(n)` counts) and at most [maxBytes] UTF-8 bytes, always on a code-point boundary - never half a surrogate pair,
 * which `String.take` would leave behind. [maxBytes] matters for a column inside a B-tree key (an entry over ~2,704 bytes fails the
 * insert; 1,024 characters alone can reach 4,096 bytes), and defaults to no byte cap for the columns that are not.
 */
internal fun cutText(text: String, maxChars: Int, maxBytes: Int = Int.MAX_VALUE): String {
    require(maxChars >= 0 && maxBytes >= 0) { "a cut cannot be negative" }
    val clean = text.replaceNul()
    var end = 0
    var chars = 0
    var bytes = 0
    while (end < clean.length) {
        val codePoint = clean.codePointAt(end)
        bytes += utf8Size(codePoint)
        if (chars == maxChars || bytes > maxBytes) break
        chars++
        end += Character.charCount(codePoint)
    }
    return clean.substring(0, end)
}
