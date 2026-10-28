package ch.nokillswit.jira

// The structural ceilings on ONE Jira response (`.claude/docs/jira-integration.md` "Response size cap", "Node and depth ceiling").
// `jira.maxResponseBytes` bounds the WIRE bytes, but a document is held parsed as a `JsonElement` tree, and a document of
// minimal objects parses to ~30x its size, while a deeply nested one overflows the parser's (and `toString()`'s) stack with a
// `StackOverflowError` no `catch (Exception)` sees. Both are bounded by one pass over the raw bytes BEFORE they are decoded or
// parsed, so a hostile document is refused while it is still only its wire size.

/**
 * Structural tokens a document may carry per wire byte: one node per [RESPONSE_BYTES_PER_NODE] bytes, 0.125 a byte. Measured on the
 * real-shaped stub pages (minified, as Jira sends them): `search/jql` pages and `changelog/bulkfetch` chunks 0.10-0.11 tokens a byte,
 * `GET /field` (the one response that cannot be halved) 0.11. It scales with the cap, so an operator who raises
 * `jira.maxResponseBytes` does not also have to raise a second knob. The denser realistic shapes (5,000 ids, 0.20; a project's statuses,
 * 0.14) are tiny pages, far below the ceiling in absolute terms at any cap.
 */
internal const val RESPONSE_BYTES_PER_NODE = 8L

/** The node ceiling that goes with a byte cap of [maxResponseBytes]: 1,048,576 tokens at the default 8 MiB. */
internal fun defaultMaxResponseNodes(maxResponseBytes: Long): Long = maxResponseBytes / RESPONSE_BYTES_PER_NODE

/** The deepest nesting a response may have. The stub's pages nest at most 7 levels; `CanonicalJsonTest` pins 500 as supported. */
internal const val MAX_RESPONSE_DEPTH = 512

/** The constant failure texts of the two ceilings (`LIMIT_EXCEEDED`, never a host value). */
internal const val NODE_CEILING_TEXT = "response over the node ceiling"
internal const val DEPTH_CEILING_TEXT = "response over the depth ceiling"

private const val QUOTE = '"'.code.toByte()
private const val BACKSLASH = '\\'.code.toByte()
private const val OPEN_BRACE = '{'.code.toByte()
private const val CLOSE_BRACE = '}'.code.toByte()
private const val OPEN_BRACKET = '['.code.toByte()
private const val CLOSE_BRACKET = ']'.code.toByte()
private const val COLON = ':'.code.toByte()
private const val COMMA = ','.code.toByte()

private fun Byte.isOpener(): Boolean = this == OPEN_BRACE || this == OPEN_BRACKET

private fun Byte.isCloser(): Boolean = this == CLOSE_BRACE || this == CLOSE_BRACKET

/** A token that costs the tree an object: a container, an entry's colon or an element's comma. */
private fun Byte.isSeparator(): Boolean = this == COLON || this == COMMA

/**
 * The constant text of the ceiling [bytes] (a UTF-8 JSON document) breaches, else null: more than [nodeLimit] structural tokens
 * ([NODE_CEILING_TEXT]) or nesting deeper than [depthLimit] ([DEPTH_CEILING_TEXT]).
 *
 * The tokens are every opening `{` or `[`, `:` and `,` outside a string: roughly one per value, entry or list slot the tree will
 * hold (a proxy for its size, not an exact object count; the heap per token varies 25-110 bytes with the shape, "Node and depth ceiling").
 * The depth is the open containers outside strings. One pass, no decoding (a UTF-8 multi-byte character's bytes are all above 0x7F,
 * so they never read as a structural byte or a quote), no allocation, stopping at the first breach. Not a validator: malformed JSON
 * is still the parser's to refuse (a stray closer cannot take the depth below zero, so it never hides a later opener).
 */
internal fun jsonCeilingBreach(bytes: ByteArray, nodeLimit: Long, depthLimit: Int = MAX_RESPONSE_DEPTH): String? {
    var nodes = 0L
    var depth = 0
    var inString = false
    var escaped = false
    for (b in bytes) {
        if (inString) {
            when {
                escaped -> escaped = false
                b == BACKSLASH -> escaped = true
                b == QUOTE -> inString = false
            }
            continue
        }
        when {
            b == QUOTE -> inString = true
            b.isOpener() -> {
                nodes++
                if (++depth > depthLimit) return DEPTH_CEILING_TEXT
            }
            b.isCloser() -> if (depth > 0) depth--
            b.isSeparator() -> nodes++
        }
        if (nodes > nodeLimit) return NODE_CEILING_TEXT
    }
    return null
}
