package ch.nokillswit.fetch

import ch.nokillswit.infra.json.sha256Hex

/** The longest `nextPageToken` accepted: real ones are a few hundred characters; one goes back in a URL and into the cursor. */
internal const val MAX_PAGE_TOKEN_CHARS = 8_192

/**
 * Refuses a `nextPageToken` that cannot be a progressing cursor, BEFORE the page it came with is stored or the token persisted
 * (`INVALID_RESPONSE`, constant text): longer than [MAX_PAGE_TOKEN_CHARS], or one the host already sent in this run (the page
 * chain cycles, so the same pages would be re-fetched until a ceiling). Remembers a SHA-256 of each token, not the token; [resumed]
 * is the token a resumed run starts from. One guard per run, never shared; a token belongs to ONE query, so [restart] (the query was
 * replaced after a `CURSOR_EXPIRED`) forgets them - the run's page and item ceilings keep counting across it.
 */
internal class PageTokenGuard(private val source: FetchSource, private val endpoint: String, resumed: String? = null) {
    private val seen = HashSet<String>()

    init {
        resumed?.let { seen += sha256Hex(it) }
    }

    fun restart() = seen.clear()

    fun accept(token: String?) {
        if (token == null) return
        if (token.length > MAX_PAGE_TOKEN_CHARS) {
            throw FetchException(source, "INVALID_RESPONSE", null, endpoint, "a page token longer than $MAX_PAGE_TOKEN_CHARS characters")
        }
        if (!seen.add(sha256Hex(token))) {
            throw FetchException(source, "INVALID_RESPONSE", null, endpoint, "a page token the host already sent in this run")
        }
    }
}
