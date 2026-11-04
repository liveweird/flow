package ch.nokillswit

import ch.nokillswit.infra.outbound.defaultTrustManager
import ch.nokillswit.infra.outbound.guardedOkHttpClient
import ch.nokillswit.infra.outbound.loadCaBundleTrust
import java.io.File
import java.nio.file.Files
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The internal CA (`gitlab.caBundle`, `infra/outbound/CaBundle.kt`): added to the GitLab client's trust ALONGSIDE the JVM defaults,
 * never to the Jira client's, never replacing anything, and no switch to turn verification off. The committed
 * `gitlab-test-ca.pem` is a throwaway self-signed CA made for this test (its key was discarded); it is no real CA.
 */
class GitLabCaBundleTest {
    private val testCaFile: File = File(assertNotNull(javaClass.getResource("/gitlab-test-ca.pem"), "test CA resource").toURI())
    private val testCa: X509Certificate =
        testCaFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private fun fingerprints(certificates: Array<X509Certificate>) = certificates.map { it.encoded.toList() }.toSet()

    private fun tempFile(content: String): File = Files.createTempFile("gitlab-ca-test", ".pem").toFile().also {
        it.writeText(content)
        it.deleteOnExit()
    }

    @Test
    fun `the GitLab trust accepts the bundle's CA and still holds every JVM default CA`() {
        val trust = loadCaBundleTrust(testCaFile.absolutePath, "gitlab.caBundle")
        val defaults = defaultTrustManager().acceptedIssuers
        assertTrue(defaults.isNotEmpty(), "the JVM has default CAs")
        val accepted = fingerprints(trust.trustManager.acceptedIssuers)
        assertTrue(testCa.encoded.toList() in accepted, "the bundle's CA is trusted")
        assertTrue(accepted.containsAll(fingerprints(defaults)), "every JVM default CA is still trusted")
        // A real PKIX validation, not just a set comparison: a chain of the self-signed test CA validates against the bundle.
        trust.trustManager.checkServerTrusted(arrayOf(testCa), "ECDSA")
    }

    @Test
    fun `the default trust (what the Jira client uses) does NOT trust the test CA`() {
        assertFalse(testCa.encoded.toList() in fingerprints(defaultTrustManager().acceptedIssuers))
        assertFailsWith<CertificateException> { defaultTrustManager().checkServerTrusted(arrayOf(testCa), "ECDSA") }
        // The guarded client without a bundle (the Jira client's factory call) carries the platform trust, not the bundle's.
        val jira = guardedOkHttpClient(allowedHost = { true }, timeoutSeconds = 5)
        assertFailsWith<CertificateException> { assertNotNull(jira.x509TrustManager).checkServerTrusted(arrayOf(testCa), "ECDSA") }
    }

    @Test
    fun `guardedOkHttpClient uses exactly the trust it is given`() {
        val trust = loadCaBundleTrust(testCaFile.absolutePath, "gitlab.caBundle")
        val gitlab = guardedOkHttpClient(
            allowedHost = { true }, timeoutSeconds = 5, sslSocketFactory = trust.sslSocketFactory, trustManager = trust.trustManager,
        )
        assertSame(trust.trustManager, gitlab.x509TrustManager)
        assertSame(trust.sslSocketFactory, gitlab.sslSocketFactory)
        // One without the other is a programming error, not a half-configured client.
        assertFailsWith<IllegalArgumentException> {
            guardedOkHttpClient(allowedHost = { true }, timeoutSeconds = 5, trustManager = trust.trustManager)
        }
        assertFailsWith<IllegalArgumentException> {
            guardedOkHttpClient(allowedHost = { true }, timeoutSeconds = 5, sslSocketFactory = trust.sslSocketFactory)
        }
    }

    @Test
    fun `a bundle of several certificates loads them all`() {
        val pem = testCaFile.readText()
        val trust = loadCaBundleTrust(tempFile(pem + "\n# a second copy\n" + pem).absolutePath, "gitlab.caBundle")
        assertTrue(testCa.encoded.toList() in fingerprints(trust.trustManager.acceptedIssuers))
    }

    private fun refusal(path: String): String =
        assertFailsWith<IllegalStateException> { loadCaBundleTrust(path, "gitlab.caBundle") }.message.orEmpty()

    @Test
    fun `an unreadable, certificate-less or malformed bundle refuses startup naming the key and never its content`() {
        val secret = "super-secret-marker-7f3a"
        val cases = mapOf(
            "a missing file" to File(System.getProperty("java.io.tmpdir"), "no-such-ca-${System.nanoTime()}.pem").absolutePath,
            "a directory" to System.getProperty("java.io.tmpdir"),
            "an empty file" to tempFile("").absolutePath,
            "text without a certificate" to tempFile("hello $secret").absolutePath,
            "a PEM block that is not a certificate" to
                tempFile("-----BEGIN PRIVATE KEY-----\n$secret\n-----END PRIVATE KEY-----\n").absolutePath,
            "a malformed certificate" to tempFile("-----BEGIN CERTIFICATE-----\n$secret\n-----END CERTIFICATE-----\n").absolutePath,
            "a truncated certificate" to
                tempFile(testCaFile.readText().lines().take(4).joinToString("\n") + "\n-----END CERTIFICATE-----\n").absolutePath,
            "a NUL byte in the path" to "ca\u0000.pem",
        )
        for ((label, path) in cases) {
            val message = refusal(path)
            assertTrue("gitlab.caBundle" in message, "$label: $message")
            assertFalse(secret in message, "$label: the file's content never reaches the error: $message")
        }
    }

    @Test
    fun `the refusal names the key it was given`() {
        val failure = assertFailsWith<IllegalStateException> { loadCaBundleTrust("/nonexistent/ca.pem", "some.other.key") }
        assertTrue("some.other.key" in failure.message.orEmpty() && "gitlab" !in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a bundle over 1 MiB is refused`() {
        val big = tempFile(testCaFile.readText() + "#".repeat(1024 * 1024 + 1))
        assertTrue("larger than" in refusal(big.absolutePath))
    }

    @Test
    fun `there is no skip-TLS or trust-all setting anywhere in the GitLab configuration or client code`() {
        val root = File(System.getProperty("repo.root") ?: "..")
        val yaml = File(root, "server/src/main/resources/application.yaml").readLines()
        val start = yaml.indexOfFirst { it == "gitlab:" }
        assertTrue(start >= 0, "application.yaml has a gitlab block")
        val block = yaml.drop(start + 1).takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("#") }
        val keys = block.filter { it.startsWith("  ") && !it.trimStart().startsWith("#") }.map { it.trim().substringBefore(':') }
        assertEquals(
            setOf(
                "allowedHosts", "allowedCidrs", "caBundle", "stubBaseUrl", "requestTimeoutSeconds", "maxResponseBytes", "maxRetries",
                "requestDeadlineSeconds", "usersRefreshHours", "commitFilesPerRun", "mrDetailsPerRun",
            ),
            keys.toSet(),
            "a new gitlab.* key is a reviewed decision",
        )
        val forbidden = Regex("(?i)trustall|insecure|hostnameverifier|skiptls|skip-tls|disabletls|sslverif|verifyssl|verifytls")
        assertTrue(keys.none { forbidden.containsMatchIn(it) })
        val sources = listOf("server/src/main/kotlin/gitlab", "server/src/main/kotlin/infra/outbound").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue(sources.size >= 8, "the scan saw ${sources.size} sources")
        val violations = sources.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                val code = line.trimStart()
                !(code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) && forbidden.containsMatchIn(code)
            }.map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList(), violations)
    }
}
