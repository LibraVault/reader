package xyz.libravault.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression guard for [SUPPORT_URL] — the one place the exact string is
 * defined, shared by both Android flavors (see SettingsScreen.kt's Support
 * button). The iOS app defines the identical string independently in
 * SettingsView.swift; keep both in sync by hand if this ever changes.
 */
class SupportLinkTest {

    @Test
    fun `support url matches the website's actual page exactly`() {
        assertEquals("https://libravault.xyz/support.html", SUPPORT_URL)
    }

    @Test
    fun `support url is https, not a scheme a malicious redirect could hijack`() {
        assertTrue(SUPPORT_URL.startsWith("https://libravault.xyz/"))
    }
}
