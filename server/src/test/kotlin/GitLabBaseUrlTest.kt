package ch.nokillswit

import ch.nokillswit.gitlab.GitLabBaseUrlException
import ch.nokillswit.gitlab.GitLabBaseUrlProblem
import ch.nokillswit.gitlab.GitLabDevStub
import ch.nokillswit.gitlab.validateGitLabBaseUrl
import ch.nokillswit.infra.outbound.SelfManagedHosts
import ch.nokillswit.infra.outbound.parseSelfManagedHosts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `gitlab/GitLabBaseUrl.kt`: the base-URL rules the data-source validator will map to 400s by their stable codes. */
class GitLabBaseUrlTest {
    private val hosts = parseSelfManagedHosts("gitlab.example.com,git.example.com:8443", "")
    private val stub = GitLabDevStub("http", "gitlab-stub", 8095)

    private fun problem(raw: String, hosts: SelfManagedHosts = this.hosts, devStub: GitLabDevStub? = null): GitLabBaseUrlProblem =
        assertFailsWith<GitLabBaseUrlException>(raw) { validateGitLabBaseUrl(raw, hosts, devStub) }.problem

    @Test
    fun `a listed https origin is accepted and canonicalised`() {
        assertEquals("https://gitlab.example.com", validateGitLabBaseUrl("https://gitlab.example.com", hosts, null).toString())
        assertEquals("https://gitlab.example.com", validateGitLabBaseUrl("https://gitlab.example.com/", hosts, null).toString())
        assertEquals("https://gitlab.example.com", validateGitLabBaseUrl("HTTPS://GitLab.Example.COM/", hosts, null).toString())
        assertEquals("https://git.example.com:8443", validateGitLabBaseUrl("https://git.example.com:8443", hosts, null).toString())
    }

    @Test
    fun `the default port 443 matches a listed host with no port, and the reverse`() {
        assertEquals("https://gitlab.example.com", validateGitLabBaseUrl("https://gitlab.example.com:443", hosts, null).toString())
        val explicit = parseSelfManagedHosts("gitlab.example.com:443", "")
        assertEquals("https://gitlab.example.com", validateGitLabBaseUrl("https://gitlab.example.com", explicit, null).toString())
        assertEquals(GitLabBaseUrlProblem.HOST_NOT_ALLOWED, problem("https://gitlab.example.com:8443"), "another port is another origin")
        assertEquals(GitLabBaseUrlProblem.HOST_NOT_ALLOWED, problem("https://git.example.com"), "an entry with a port means that port only")
        assertEquals(GitLabBaseUrlProblem.HOST_NOT_ALLOWED, problem("https://git.example.com:443"))
    }

    @Test
    fun `an unlisted host is HOST_NOT_ALLOWED and an empty list is DISABLED`() {
        assertEquals(GitLabBaseUrlProblem.HOST_NOT_ALLOWED, problem("https://evil.example.net"))
        assertEquals(GitLabBaseUrlProblem.HOST_NOT_ALLOWED, problem("https://gitlab.example.com.evil.net"))
        assertEquals(GitLabBaseUrlProblem.HOST_NOT_ALLOWED, problem("https://sub.gitlab.example.com"))
        assertEquals(GitLabBaseUrlProblem.HOST_NOT_ALLOWED, problem("https://10.20.1.5"), "an IP literal is never listed")
        assertEquals(GitLabBaseUrlProblem.HOST_NOT_ALLOWED, problem("https://[::1]"))
        assertEquals(GitLabBaseUrlProblem.DISABLED, problem("https://gitlab.example.com", SelfManagedHosts.NONE))
    }

    @Test
    fun `http is refused, even for a listed host`() {
        assertEquals(GitLabBaseUrlProblem.SCHEME, problem("http://gitlab.example.com"))
        assertEquals(GitLabBaseUrlProblem.SCHEME, problem("ftp://gitlab.example.com"))
        assertEquals(GitLabBaseUrlProblem.SCHEME, problem("http://gitlab-stub:8095"), "the stub origin needs a configured stub")
    }

    @Test
    fun `the dev stub origin may be http, and only that origin`() {
        assertEquals("http://gitlab-stub:8095", validateGitLabBaseUrl("http://gitlab-stub:8095", hosts, stub).toString())
        assertEquals("http://gitlab-stub:8095", validateGitLabBaseUrl("http://gitlab-stub:8095/", SelfManagedHosts.NONE, stub).toString())
        assertEquals(GitLabBaseUrlProblem.SCHEME, problem("http://gitlab-stub:8096", devStub = stub))
        assertEquals(GitLabBaseUrlProblem.SCHEME, problem("http://gitlab-stub", devStub = stub), "no port means 80, not the stub's port")
        assertEquals(GitLabBaseUrlProblem.SCHEME, problem("http://gitlab.example.com", devStub = stub))
    }

    @Test
    fun `anything beyond an origin is NOT_AN_ORIGIN`() {
        for (raw in listOf(
            "https://gitlab.example.com/gitlab", "https://gitlab.example.com/a/b", "https://gitlab.example.com//",
            "https://gitlab.example.com?x=1", "https://gitlab.example.com/?", "https://gitlab.example.com/#frag",
            "https://gitlab.example.com#", "https://user@gitlab.example.com", "https://user:pw@gitlab.example.com",
            "https://:pw@gitlab.example.com", "https://gitlab.example.com/;a=b",
        )) {
            assertEquals(GitLabBaseUrlProblem.NOT_AN_ORIGIN, problem(raw), raw)
        }
    }

    @Test
    fun `a malformed URL is INVALID`() {
        for (raw in listOf(
            "", " ", "gitlab.example.com", "//gitlab.example.com", "https://", "https:gitlab.example.com", "https:///path",
            " https://gitlab.example.com", "https://gitlab.example.com ", "https://gitlab .example.com", "https://gitlab.example.com:/",
            "https://gitlab.example.com:abc", "https://gitlab.example.com:99999999999", "https://gitlab.example.com\\@evil.net",
            "https://exa%6dple.com", "https://gitlab.example.com\n", "https://gitlab.example.com:443:443",
        )) {
            val failure = assertFailsWith<GitLabBaseUrlException>(raw) { validateGitLabBaseUrl(raw, hosts, null) }
            assertEquals(GitLabBaseUrlProblem.INVALID, failure.problem, raw)
        }
    }

    @Test
    fun `the exception carries a stable code and never the URL`() {
        val failure = assertFailsWith<GitLabBaseUrlException> { validateGitLabBaseUrl("https://user:pw@gitlab.example.com", hosts, null) }
        assertEquals("gitlab-base-url-not-origin", failure.code)
        assertEquals(false, failure.message.orEmpty().contains("pw"))
        assertEquals(
            setOf(
                "gitlab-base-url-invalid", "gitlab-base-url-scheme", "gitlab-base-url-not-origin",
                "gitlab-disabled", "gitlab-host-not-allowed",
            ),
            GitLabBaseUrlProblem.entries.map { it.code }.toSet(),
        )
    }
}
