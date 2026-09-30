package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Testcontainers Postgres and the compose/k8s Postgres must be the SAME bytes
 * (`.claude/docs/dependencies.md` — "Keep PostgreSQL on the same tested major/minor across Compose,
 * Kubernetes and Testcontainers"). The tag alone is mutable; the digest selects the bytes, so the
 * test asserts the full `image:` reference matches, not just the version.
 */
class PostgresImagePinTest {
    // `repo.root` is set by server/build.gradle.kts; the `..` fallback covers an IDE run from `server/`.
    private fun repoFile(path: String): File =
        File(System.getProperty("repo.root") ?: "..", path).also { assertTrue(it.exists(), "$path not found (${it.absolutePath})") }

    private fun postgresImageIn(path: String): String =
        Regex("""image:\s*(postgres:\S+)""").find(repoFile(path).readText())?.groupValues?.get(1)
            ?: error("no postgres image in $path")

    @Test
    fun `the Testcontainers postgres image is the one compose and k8s pin`() {
        val compose = postgresImageIn("docker-compose.yaml")
        assertTrue("@sha256:" in compose, "compose must pin a digest: $compose")
        assertEquals(compose, PostgresTestSupport.IMAGE)
        assertEquals(compose, postgresImageIn("k8s/postgres-deployment.yaml"))
    }
}
