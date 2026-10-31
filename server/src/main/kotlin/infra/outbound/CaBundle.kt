package ch.nokillswit.infra.outbound

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** A client's own TLS trust: the socket factory and the trust manager that must be handed to OkHttp together. */
class TlsTrust(val sslSocketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

/** A PEM bundle bigger than this is not a CA bundle (the cap also stops a config pointing at an endless device file). */
private const val MAX_BUNDLE_BYTES = 1L * 1024 * 1024

/**
 * Builds the GitLab client's trust (`gitlab.caBundle`, `.claude/docs/security.md` "Outbound HTTP calls"): the JVM's default
 * trusted CAs PLUS every certificate in the PEM bundle at [path] — an internal CA is added, never substituted, so a host
 * with a public certificate keeps working. The default trust manager is built the way the JVM builds it (honouring a
 * `javax.net.ssl.trustStore` override), its anchors are copied into a fresh in-memory KeyStore with the bundle's, and a
 * standard PKIX trust manager is built over that. Certificate and hostname verification stay on: this has no "trust all".
 *
 * Throws [IllegalStateException] (startup refuses, naming the key and never the file's content) for an unreadable file, one
 * past [MAX_BUNDLE_BYTES], a malformed one, or one that holds no X.509 certificate.
 */
fun loadCaBundleTrust(path: String): TlsTrust {
    val certificates = readCertificates(path)
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    defaultTrustManager().acceptedIssuers.forEachIndexed { i, anchor -> store.setCertificateEntry("default-$i", anchor) }
    certificates.forEachIndexed { i, certificate -> store.setCertificateEntry("bundle-$i", certificate) }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    val trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return TlsTrust(context.socketFactory, trustManager)
}

/** The JVM's default X.509 trust manager (what a client built without [TlsTrust] uses). */
fun defaultTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}

private fun readCertificates(path: String): List<X509Certificate> {
    val file = try {
        Path.of(path)
    } catch (_: java.nio.file.InvalidPathException) {
        error("Config \"gitlab.caBundle\" is not a valid file path")
    }
    val bytes = try {
        check(Files.isRegularFile(file)) { "Config \"gitlab.caBundle\" does not point at a readable file" }
        check(Files.size(file) <= MAX_BUNDLE_BYTES) { "Config \"gitlab.caBundle\" is larger than ${MAX_BUNDLE_BYTES / 1024} KiB" }
        Files.readAllBytes(file)
    } catch (_: IOException) {
        error("Config \"gitlab.caBundle\" does not point at a readable file")
    }
    val certificates = try {
        CertificateFactory.getInstance("X.509").generateCertificates(bytes.inputStream()).filterIsInstance<X509Certificate>()
    } catch (_: GeneralSecurityException) {
        error("Config \"gitlab.caBundle\" is not a valid PEM bundle of X.509 certificates")
    }
    check(certificates.isNotEmpty()) { "Config \"gitlab.caBundle\" holds no X.509 certificate" }
    return certificates
}
