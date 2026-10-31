package ch.nokillswit

import ch.nokillswit.infra.validation.PathGlob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `PathGlob` (L11): gitignore-like path globs - the 14 built-in defaults, the semantics rules and the compile failures. */
class PathGlobTest {

    private val defaults = listOf(
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "go.sum", "Cargo.lock", "Gemfile.lock", "poetry.lock", "composer.lock",
        "*.min.js", "*.min.css", "**/vendor/**", "**/node_modules/**", "**/dist/**", "**/generated/**",
    ).map(PathGlob::compile)

    private fun glob(pattern: String) = PathGlob.compile(pattern)

    private fun assertMatches(pattern: String, vararg paths: String) =
        paths.forEach { assertTrue(glob(pattern).matches(it), "'$pattern' should match '$it'") }

    private fun assertNoMatch(pattern: String, vararg paths: String) =
        paths.forEach { assertFalse(glob(pattern).matches(it), "'$pattern' should not match '$it'") }

    @Test
    fun `the 14 defaults cover the stub's generated and vendored paths`() {
        assertEquals(14, defaults.size)
        listOf(
            "go.sum", "static/app.min.js", "vendor/github.com/sample/httpmux/resolver.go", "web/dist/styles.css",
            "node_modules/x/y.js", "generated/api.ts", "package-lock.json", "web/package-lock.json", "a/b/yarn.lock",
            "pnpm-lock.yaml", "Cargo.lock", "services/x/Gemfile.lock", "poetry.lock", "composer.lock", "css/site.min.css",
            "a/vendor/b.go", "a/b/node_modules/c/d.js", "a/b/dist/c", "x/y/generated/z.ts",
        ).forEach { assertTrue(PathGlob.matchesAny(defaults, it), "a default should match '$it'") }
    }

    @Test
    fun `the 14 defaults leave ordinary source files alone`() {
        listOf(
            "src/data/README.md", "internal/auth/limit.go", "docs/api/reference.md", "distribution/x", "README.md",
            "src/vendors/x.go", "src/node_modules_notes.md", "src/generated.ts", "go.sum.txt", "app.min.js.map", "src/min.js",
        ).forEach { assertFalse(PathGlob.matchesAny(defaults, it), "no default should match '$it'") }
        assertFalse(PathGlob.matchesAny(emptyList(), "go.sum"), "an empty list excludes nothing")
    }

    @Test
    fun `a pattern without a slash matches the file name at any depth`() {
        assertMatches("go.sum", "go.sum", "a/go.sum", "a/b/c/go.sum")
        assertNoMatch("go.sum", "go.sum/x", "a/go.sum/x", "xgo.sum", "a/mygo.sum")
        assertMatches("*.min.js", "app.min.js", "static/app.min.js", ".min.js")
        assertNoMatch("*.min.js", "app.js", "static.min.js/app.js", "app.min.jsx")
    }

    @Test
    fun `a pattern with a slash matches the whole path`() {
        assertMatches("docs/*.md", "docs/a.md")
        assertNoMatch("docs/*.md", "docs/sub/a.md", "x/docs/a.md", "docs/a.txt")
        assertMatches("src/**/gen.go", "src/gen.go", "src/a/gen.go", "src/a/b/gen.go")
        assertNoMatch("src/**/gen.go", "other/src/gen.go", "src/gen.gox")
        assertMatches("src/**", "src/a", "src/a/b/c.go")
        assertNoMatch("src/**", "srcs/a", "other/src/a")
    }

    @Test
    fun `a leading slash anchors the pattern at the root`() {
        assertMatches("/go.sum", "go.sum")
        assertNoMatch("/go.sum", "sub/go.sum")
        assertMatches("/docs/*.md", "docs/a.md")
    }

    @Test
    fun `a trailing slash names a directory`() {
        assertMatches("dist/", "dist/a.js", "web/dist/a.js", "web/dist/x/y.js")
        assertNoMatch("dist/", "dist", "distribution/x", "web/dist.js")
        assertMatches("web/dist/", "web/dist/a.js", "web/dist/x/y.js")
        assertNoMatch("web/dist/", "x/web/dist/a.js", "web/other/a.js")
        assertMatches("/dist/", "dist/a.js")
        assertNoMatch("/dist/", "web/dist/a.js")
    }

    @Test
    fun `double star crosses slashes and a single star does not`() {
        assertMatches("**/vendor/**", "vendor/a", "a/vendor/b", "a/b/vendor/c/d")
        assertNoMatch("**/vendor/**", "vendor", "vendors/a", "a/vendor")
        assertMatches("a/*/c", "a/b/c")
        assertNoMatch("a/*/c", "a/b/b/c", "a/c")
        assertMatches("a/**/c", "a/c", "a/b/c", "a/b/b/c")
        assertMatches("a/**/**/c", "a/c", "a/b/b/c")
        assertMatches("a**b.txt", "a-b.txt", "axxb.txt")
        assertNoMatch("a**b.txt", "a/b.txt", "a/x/b.txt")
        assertMatches("**", "x", "a/b/c")
    }

    @Test
    fun `question mark is exactly one non-slash character`() {
        assertMatches("file?.txt", "file1.txt", "src/fileA.txt")
        assertNoMatch("file?.txt", "file.txt", "file12.txt")
        assertNoMatch("a?b/c", "a/b/c")
    }

    @Test
    fun `character classes match one character and never the slash`() {
        assertMatches("file[0-9].txt", "file7.txt", "x/file0.txt")
        assertNoMatch("file[0-9].txt", "filea.txt", "file77.txt")
        assertMatches("file[!0-9].txt", "filea.txt")
        assertNoMatch("file[!0-9].txt", "file1.txt")
        assertMatches("file[^ab].txt", "filec.txt")
        assertNoMatch("file[^ab].txt", "filea.txt")
        assertMatches("a[]x]b", "a]b", "axb")
        assertMatches("a[-x]b", "a-b", "axb")
        assertMatches("a[&&]b", "a&b")
        assertNoMatch("a[^x]b/c", "a/b/c")
    }

    @Test
    fun `a backslash makes the next character literal`() {
        assertMatches("a\\*b", "a*b")
        assertNoMatch("a\\*b", "axb")
        assertMatches("a\\?b.txt", "a?b.txt")
        assertMatches("\\[x]", "[x]")
    }

    @Test
    fun `regex metacharacters in a pattern are literal`() {
        assertMatches("a.b+c(d)", "a.b+c(d)")
        assertNoMatch("a.b", "axb")
        assertMatches("a|b", "a|b")
        assertNoMatch("a|b", "a", "b")
        assertMatches("\\E.txt", "E.txt") // an escaped letter that is also a regex quoting command
    }

    @Test
    fun `matching is case sensitive`() {
        assertNoMatch("Cargo.lock", "cargo.lock", "CARGO.LOCK")
        assertNoMatch("*.min.js", "app.MIN.js")
        assertNoMatch("dist/", "Dist/a.js")
        assertNoMatch("file[a-c].txt", "fileB.txt")
    }

    @Test
    fun `a path with unusual characters still matches`() {
        assertMatches("**/generated/**", "a b/generated/ünï.go", "x/generated/line\nbreak.go")
        assertMatches("*.lock", "weird\nname.lock")
    }

    @Test
    fun `the pattern text is kept as given`() {
        assertEquals("**/dist/**", glob("**/dist/**").pattern)
        assertEquals("/dist/", glob("/dist/").toString())
    }

    @Test
    fun `an invalid pattern fails to compile`() {
        listOf("", "/", "//", "a//b", "dist//", "[abc", "a[]", "a[!]", "[z-a]", "abc\\", "a/[b", "a\\").forEach { pattern ->
            assertFailsWith<IllegalArgumentException>("'$pattern' should not compile") { PathGlob.compile(pattern) }
        }
        glob("a/") // a trailing slash alone is the valid directory `a`
    }
}
