package com.phairplay.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppSettingsTest — Unit tests for [AppSettings] data class.
 *
 * WHY: [AppSettings] contains computed properties and constants that are
 * used throughout the app. Regression tests ensure that default values,
 * validation logic, and computed properties always behave correctly.
 *
 * WHAT WE TEST:
 * - Default values match the design spec
 * - [AppSettings.effectiveDisplayName] trims whitespace correctly
 * - [AppSettings.anyProtocolEnabled] returns correct results for all combinations
 * - [AppSettings.DISPLAY_NAME_MAX_LENGTH] constant value
 * - copy() semantics (standard data class behaviour)
 */
class AppSettingsTest {

    // ─── Default values ──────────────────────────────────────────────────────

    @Test
    fun `default settings have empty display name`() {
        assertEquals("", AppSettings.DEFAULT.displayName)
    }

    @Test
    fun `default settings have all protocols enabled`() {
        assertTrue(AppSettings.DEFAULT.airPlayEnabled)
        assertTrue(AppSettings.DEFAULT.castEnabled)
    }

    @Test
    fun `default settings have pin auth disabled`() {
        assertFalse(AppSettings.DEFAULT.airPlayPinAuthEnabled)
    }

    @Test
    fun `default settings have start on boot disabled`() {
        assertFalse(AppSettings.DEFAULT.startOnBoot)
    }

    @Test
    fun `default settings have debug overlay disabled`() {
        assertFalse(AppSettings.DEFAULT.showDebugOverlay)
    }

    @Test
    fun `DISPLAY_NAME_MAX_LENGTH is 63`() {
        assertEquals(63, AppSettings.DISPLAY_NAME_MAX_LENGTH)
    }

    // ─── effectiveDisplayName ─────────────────────────────────────────────────

    @Test
    fun `effectiveDisplayName returns trimmed name`() {
        val settings = AppSettings(displayName = "  Living Room TV  ")
        assertEquals("Living Room TV", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName returns empty string for blank name`() {
        val settings = AppSettings(displayName = "   ")
        assertEquals("", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName returns name unchanged when no surrounding whitespace`() {
        val settings = AppSettings(displayName = "PhairPlay")
        assertEquals("PhairPlay", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName handles empty string`() {
        val settings = AppSettings(displayName = "")
        assertEquals("", settings.effectiveDisplayName)
    }

    // ─── anyProtocolEnabled ───────────────────────────────────────────────────

    @Test
    fun `anyProtocolEnabled is true when all protocols are enabled`() {
        val settings = AppSettings(airPlayEnabled = true, castEnabled = true)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only AirPlay is enabled`() {
        val settings = AppSettings(airPlayEnabled = true, castEnabled = false)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only Cast is enabled`() {
        val settings = AppSettings(airPlayEnabled = false, castEnabled = true)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is false when all protocols are disabled`() {
        val settings = AppSettings(airPlayEnabled = false, castEnabled = false)
        assertFalse(settings.anyProtocolEnabled)
    }

    // ─── copy() and equality ──────────────────────────────────────────────────

    @Test
    fun `copy preserves unchanged fields`() {
        val original = AppSettings(displayName = "TV", airPlayEnabled = true, startOnBoot = false)
        val updated = original.copy(startOnBoot = true)

        assertEquals("TV", updated.displayName)
        assertTrue(updated.airPlayEnabled)
        assertTrue(updated.startOnBoot)
    }

    @Test
    fun `two instances with same values are equal`() {
        val a = AppSettings(displayName = "TV", airPlayEnabled = true)
        val b = AppSettings(displayName = "TV", airPlayEnabled = true)
        assertEquals(a, b)
    }

    @Test
    fun `two instances with different values are not equal`() {
        val a = AppSettings(displayName = "TV1")
        val b = AppSettings(displayName = "TV2")
        assertTrue(a != b)
    }
}
