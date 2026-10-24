package ch.nokillswit

import ch.nokillswit.infra.mail.validatedAppUrlOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `validatedAppUrlOrigin` (`infra/mail/Mail.kt`): the boot-time shape check of the origin the reset link is built from. */
class MailAppUrlTest {
    @Test
    fun `blank is no origin and an https origin is trimmed of its trailing slash and keeps its port`() {
        assertNull(validatedAppUrlOrigin("", development = false))
        assertNull(validatedAppUrlOrigin("   ", development = true))
        assertEquals("https://flow.example.com", validatedAppUrlOrigin("https://flow.example.com/", development = false))
        assertEquals("https://flow.example.com", validatedAppUrlOrigin("https://flow.example.com", development = false))
        assertEquals("https://flow.example.com:8443", validatedAppUrlOrigin("https://flow.example.com:8443/", development = false))
        assertEquals("https://h:65535", validatedAppUrlOrigin("https://h:65535", development = false))
    }

    @Test
    fun `http is a development-only scheme`() {
        assertEquals("http://localhost:8084", validatedAppUrlOrigin("http://localhost:8084", development = true))
        assertFailsWith<IllegalStateException> { validatedAppUrlOrigin("http://localhost:8084", development = false) }
    }

    @Test
    fun `the scheme is case-insensitive and normalised to lower case`() {
        assertEquals("https://Flow.Example.com", validatedAppUrlOrigin("HTTPS://Flow.Example.com/", development = false))
        assertEquals("http://h", validatedAppUrlOrigin("Http://h", development = true))
        assertFailsWith<IllegalStateException> { validatedAppUrlOrigin("HTTP://h", development = false) }
    }

    @Test
    fun `an empty, zero or out-of-range port and every non-origin shape are refused`() {
        for (bad in listOf(
            "https://h:", "https://h:/", "https://h:0", "https://h:65536", "https://h:-1",
            "https://h/path", "https://u@h", "https://h?x", "https://h#f", "ftp://h", "not a url", "https:///", "//h", "h",
        )) {
            val failure = assertFailsWith<IllegalStateException>("for '$bad'") { validatedAppUrlOrigin(bad, development = true) }
            if (bad.length > 3) assertEquals(false, bad in (failure.message ?: ""), "the message never echoes the value")
        }
        assertFailsWith<IllegalStateException> { validatedAppUrlOrigin("https://" + "h".repeat(2048), development = true) }
    }
}
