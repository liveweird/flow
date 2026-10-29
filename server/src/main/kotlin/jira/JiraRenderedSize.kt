package ch.nokillswit.jira

// What a stored text weighs once PostgreSQL renders it back (`payload::text`, which PROCESS's claim, the raw issue inspector and every
// whole-kind read measure with `octet_length`): `jsonb` keeps a number as a `numeric`, and renders it in PLAIN digits, so
// `'[1e131071]'::jsonb` is 22 bytes stored but 131,074 bytes as text, and it writes a space after every `,` and `:` outside strings
// (`'[0,0,0,0,0]'::jsonb::text` is 15 bytes against 11). The stored-size ceilings therefore bound this estimate, not the canonical
// text's own length. Real data does contain exponent-looking text (`1.0E7` costs seven digits), so an exponent alone refuses
// nothing: only an item whose estimate is over its ceiling is refused.

/** The most digits one number literal can add (`numeric` has at most 131,072 digits before the point: larger ones). */
private const val MAX_EXPONENT_DIGITS = 131_072L

/**
 * The UTF-8 size of this JSON text plus what PostgreSQL's rendering adds: one space per `,` and `:` outside strings, and for every NUMBER
 * literal (outside strings) with an exponent the digits its plain form needs beyond the literal - roughly the exponent's magnitude,
 * capped at [MAX_EXPONENT_DIGITS] a literal. One linear scan; strings are skipped whole, so exponent-looking text inside a string costs
 * nothing. Never below `octet_length(text::jsonb::text)` (`JiraStoredCeilingTest` compares it with PostgreSQL itself).
 */
internal fun String.renderedBytes(): Long = utf8Length() + renderingExtras()

private fun String.renderingExtras(): Long {
    var extra = 0L
    var index = 0
    while (index < length) {
        val c = this[index]
        index = when {
            c == '"' -> skipString(index)
            c == ',' || c == ':' -> {
                extra++
                index + 1
            }
            c in '0'..'9' || c == '-' -> {
                val end = numberEnd(index)
                extra += exponentOf(index, end)
                end
            }
            else -> index + 1
        }
    }
    return extra
}

/** The index after the string literal opening at [open] (an unterminated one ends at the end of the text). */
private fun String.skipString(open: Int): Int {
    var index = open + 1
    while (index < length && this[index] != '"') index += if (this[index] == '\\') 2 else 1
    return index + 1
}

/** The non-digit characters a JSON number literal is made of. */
private val NUMBER_PUNCTUATION = setOf('.', '+', '-', 'e', 'E')

private fun String.numberEnd(start: Int): Int {
    var end = start + 1
    while (end < length && (this[end] in '0'..'9' || this[end] in NUMBER_PUNCTUATION)) end++
    return end
}

/** The capped magnitude of the exponent of the number literal in `[start, end)`, `0` when it has none. */
private fun String.exponentOf(start: Int, end: Int): Long {
    var index = start
    while (index < end && this[index] != 'e' && this[index] != 'E') index++
    if (index >= end) return 0
    index++
    if (index < end && (this[index] == '+' || this[index] == '-')) index++
    var magnitude = 0L
    while (index < end && this[index] in '0'..'9' && magnitude < MAX_EXPONENT_DIGITS) magnitude = magnitude * 10 + (this[index++] - '0')
    return minOf(magnitude, MAX_EXPONENT_DIGITS)
}
