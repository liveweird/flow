package ch.nokillswit

import ch.nokillswit.auth.PASSWORD_RESET_EMAIL_SUBJECT
import ch.nokillswit.auth.passwordResetEmailBody
import ch.nokillswit.infra.mail.LocalizedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests of the per-language email builders (thin wrappers over
 * infra/mail/PasswordEmail.kt, exercised through their real content) — the recipient's stored
 * per-user language (V1 `users.language`) drives both, EN falling back for an unknown code.
 */
class LocalizedEmailTest {

    @Test
    fun `LocalizedText resolves both languages and falls back to English`() {
        val text = LocalizedText(en = "EN", pl = "PL")
        assertEquals("EN", text.of("en"))
        assertEquals("PL", text.of("pl"))
        assertEquals("EN", text.of("xx"), "an unknown code falls back to English")
    }

    @Test
    fun `password-reset body renders one language with the password and the dead-password warning`() {
        val en = passwordResetEmailBody("Alice", "s3cret-P4ss_word", appUrl = null, language = "en")
        assertTrue("Hi Alice," in en)
        assertFalse("Cześć" in en, "the EN body must not carry Polish")
        assertTrue("New password:" in en)
        assertTrue("s3cret-P4ss_word" in en)
        assertTrue("no longer works" in en)

        val pl = passwordResetEmailBody("Alice", "s3cret-P4ss_word", appUrl = null, language = "pl")
        assertTrue("Cześć Alice," in pl)
        assertFalse("Hi Alice," in pl, "the PL body must not carry English")
        assertTrue("Nowe hasło:" in pl)
        assertTrue("już nie działa" in pl)

        assertEquals("Your new Flow password", PASSWORD_RESET_EMAIL_SUBJECT.of("en"))
        assertEquals("Twoje nowe hasło Flow", PASSWORD_RESET_EMAIL_SUBJECT.of("pl"))
    }

    @Test
    fun `the sign-in link renders only when the deployment URL is configured`() {
        val withUrl = passwordResetEmailBody("Ann", "pw", appUrl = "https://flow.example", language = "en")
        assertTrue("Sign in: https://flow.example" in withUrl)
        // The intro copy also says "Sign in with the new password" — assert on the LINK line.
        val withoutUrl = passwordResetEmailBody("Ann", "pw", appUrl = null, language = "en")
        assertFalse("Sign in:" in withoutUrl)
    }
}
