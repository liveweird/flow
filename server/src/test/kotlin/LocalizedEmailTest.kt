package ch.nokillswit

import ch.nokillswit.auth.PASSWORD_RESET_COMPLETED_BODY
import ch.nokillswit.auth.PASSWORD_RESET_COMPLETED_SUBJECT
import ch.nokillswit.auth.PASSWORD_RESET_EMAIL_SUBJECT
import ch.nokillswit.auth.passwordResetEmailBody
import ch.nokillswit.infra.mail.LocalizedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests of the per-language email builders (`auth/PasswordResetEmail.kt`, exercised through their real content) -
 * the recipient's stored per-user language (V1 `users.language`) drives both, EN falling back for an unknown code.
 */
class LocalizedEmailTest {
    private val token = "A".repeat(43)

    @Test
    fun `LocalizedText resolves both languages and falls back to English`() {
        val text = LocalizedText(en = "EN", pl = "PL")
        assertEquals("EN", text.of("en"))
        assertEquals("PL", text.of("pl"))
        assertEquals("EN", text.of("xx"), "an unknown code falls back to English")
    }

    @Test
    fun `the reset-link body renders one language with the link, the lifetime and no password`() {
        val link = "https://flow.example/reset-password/confirm#token=$token"
        val en = passwordResetEmailBody("Alice", token, "https://flow.example", 900_000, "en")
        assertTrue("Hi Alice," in en)
        assertFalse("Cześć" in en, "the EN body must not carry Polish")
        assertTrue("Your password is unchanged" in en)
        assertTrue("single-use link" in en)
        assertTrue("Link lifetime (minutes): 15" in en)
        assertTrue(link in en, "the link carries the token in the fragment")
        assertFalse("New password" in en, "no email carries a password any more")

        val pl = passwordResetEmailBody("Alice", token, "https://flow.example", 900_000, "pl")
        assertTrue("Cześć Alice," in pl)
        assertFalse("Hi Alice," in pl, "the PL body must not carry English")
        assertTrue("Twoje hasło pozostaje bez zmian" in pl)
        assertTrue("jednorazowego linku" in pl)
        assertTrue("Ważność linku (minuty): 15" in pl)
        assertTrue(link in pl)
    }

    @Test
    fun `the lifetime line rounds up to whole minutes`() {
        assertTrue("(minutes): 1\n" in passwordResetEmailBody("Ann", token, "https://f", 1_000, "en"))
        assertTrue("(minutes): 2\n" in passwordResetEmailBody("Ann", token, "https://f", 61_000, "en"))
        assertTrue("(minutes): 60\n" in passwordResetEmailBody("Ann", token, "https://f", 3_600_000, "en"))
    }

    @Test
    fun `the subjects and the post-reset notice exist in both languages and carry no token`() {
        assertEquals("Reset your Flow password", PASSWORD_RESET_EMAIL_SUBJECT.of("en"))
        assertEquals("Zresetuj hasło Flow", PASSWORD_RESET_EMAIL_SUBJECT.of("pl"))
        assertEquals("Your Flow password was reset", PASSWORD_RESET_COMPLETED_SUBJECT.of("en"))
        assertEquals("Twoje hasło Flow zostało zresetowane", PASSWORD_RESET_COMPLETED_SUBJECT.of("pl"))
        assertTrue("existing sessions were signed out" in PASSWORD_RESET_COMPLETED_BODY.of("en"))
        assertTrue("istniejące sesje zostały wylogowane" in PASSWORD_RESET_COMPLETED_BODY.of("pl"))
        assertFalse("reset-password" in PASSWORD_RESET_COMPLETED_BODY.of("en"), "the notice carries no link")
    }
}
