package ch.nokillswit.infra.validation

// A gitignore-like glob over a repository file path (`internal/auth/limit.go`: no leading slash, `/` the separator). Used to
// validate a data source's path exclusions today and to match them when a commit is processed. Semantics, case-sensitive:
// - A pattern WITHOUT a slash matches the file NAME (the last path segment) at any depth: `package-lock.json`, `*.min.js`.
// - A pattern WITH a slash (anywhere, a leading one included) matches the WHOLE path from the root: `docs/*.md`. A leading slash
//   only anchors and is not part of the match: `/go.sum` matches `go.sum` but not `sub/go.sum`.
// - A trailing slash names a directory: `dist/` is `**` `/dist/` `**` (a directory of that name at any depth), while `web/dist/`
//   is anchored (it has another slash) and means everything beneath `web/dist`.
// - `*` and `?` stay inside one segment (any run of / exactly one character other than the slash); `[abc]`, `[a-z]` and
//   `[!abc]` / `[^abc]` are character classes that never match the slash; a backslash makes the next character literal outside
//   a class (inside one it is itself literal); everything else is literal.
// - `**` as a WHOLE segment crosses the slash: leading or in the middle it matches zero or more directories, trailing it matches
//   everything beneath. Inside a longer segment (`a**b`) it is an ordinary `*`.
// `compile` throws IllegalArgumentException for an empty pattern, an empty segment (`a//b`), an unclosed or empty `[`, a
// malformed class range or a backslash at the end. Patterns are admin-curated and at most a few hundred characters, so the
// backtracking cost of stacked `*` is bounded by validation, not by this class.

/** A compiled path glob (semantics above); build one with [compile], test a path with [matches]. */
class PathGlob private constructor(private val regex: Regex, private val nameOnly: Boolean, val pattern: String) {

    /** True when [path] (a repository-relative file path) is covered by this pattern. */
    fun matches(path: String): Boolean = regex.matches(if (nameOnly) path.substringAfterLast('/') else path)

    override fun toString(): String = pattern

    companion object {
        private const val CLASS_METACHARACTERS = "\\[]&^"

        /** Compiles [pattern]; the exact text is kept as [PathGlob.pattern]. */
        fun compile(pattern: String): PathGlob {
            val directory = pattern.endsWith("/")
            val anchored = pattern.startsWith("/")
            val body = pattern.removePrefix("/").removeSuffix("/")
            require(body.isNotEmpty()) { "A path pattern must not be empty" }
            val nameOnly = !directory && !anchored && '/' !in body
            val segments = body.split('/').toMutableList()
            if (directory) segments += "**"
            if (directory && !anchored && segments.size == 2) segments.add(0, "**")
            val regex = try {
                Regex(translate(segments), RegexOption.DOT_MATCHES_ALL)
            } catch (invalid: java.util.regex.PatternSyntaxException) {
                throw IllegalArgumentException("Invalid path pattern: ${invalid.description}", invalid)
            }
            return PathGlob(regex, nameOnly, pattern)
        }

        /** True when any of [globs] covers [path]. */
        fun matchesAny(globs: List<PathGlob>, path: String): Boolean = globs.any { it.matches(path) }

        private fun translate(segments: List<String>): String {
            val out = StringBuilder()
            require(segments.none { it.isEmpty() }) { "A path pattern must not contain an empty segment" }
            // `**/**` is `**`: collapse the runs so an optional group never stacks on another.
            val collapsed = segments.filterIndexed { index, segment -> !(segment == "**" && index > 0 && segments[index - 1] == "**") }
            collapsed.forEachIndexed { index, segment ->
                val last = index == collapsed.lastIndex
                when {
                    segment == "**" && last -> out.append(".*")
                    segment == "**" -> out.append("(?:.*/)?")
                    else -> {
                        out.append(translateSegment(segment))
                        if (!last) out.append('/')
                    }
                }
            }
            return out.toString()
        }

        private fun translateSegment(segment: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < segment.length) {
                val c = segment[i]
                when (c) {
                    '*' -> {
                        while (i + 1 < segment.length && segment[i + 1] == '*') i++
                        out.append("[^/]*")
                    }
                    '?' -> out.append("[^/]")
                    '[' -> i = appendClass(segment, i, out)
                    '\\' -> {
                        require(i + 1 < segment.length) { "A path pattern must not end with a backslash" }
                        i++
                        out.append(Regex.escape(segment[i].toString()))
                    }
                    else -> out.append(Regex.escape(c.toString()))
                }
                i++
            }
            return out.toString()
        }

        /** Appends the class opening at [open] and returns the index of its closing `]`. */
        private fun appendClass(segment: String, open: Int, out: StringBuilder): Int {
            var i = open + 1
            val negated = i < segment.length && (segment[i] == '!' || segment[i] == '^')
            if (negated) i++
            val body = StringBuilder()
            while (i < segment.length && (segment[i] != ']' || body.isEmpty())) {
                val c = segment[i]
                // `-` stays the range operator; the characters that mean something inside a Java class are escaped.
                if (c in CLASS_METACHARACTERS) body.append('\\')
                body.append(c)
                i++
            }
            require(i < segment.length) { "A path pattern has an unclosed [" }
            out.append("(?!/)[").append(if (negated) "^" else "").append(body).append(']')
            return i
        }
    }
}
