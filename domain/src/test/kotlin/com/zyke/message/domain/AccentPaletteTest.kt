package com.zyke.message.domain

import com.zyke.message.domain.model.AccentColor
import com.zyke.message.domain.model.AccentPalette
import com.zyke.message.domain.model.AccentRoles
import com.zyke.message.domain.model.AppSettings
import com.zyke.message.domain.model.ColorMath
import com.zyke.message.domain.model.Contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPaletteTest {
    private val seeds = listOf(
        0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL, 0xFFFFFF00L, 0xFFFFFFFFL, 0xFF000000L, 0xFF808080L,
        0xFF39FF14L, 0xFFFF10F0L, 0xFF00FFFFL, 0xFF0D0D0DL, 0xFFF5F5F5L, 0xFF6650A4L, 0xFFFFD700L, 0xFF7F00FFL
    ) + (0 until 360 step 15).flatMap { h ->
        listOf(0.2, 0.6, 1.0).flatMap { s -> listOf(0.2, 0.55, 0.9, 1.0).map { v -> ColorMath.fromHsv(h.toDouble(), s, v) } }
    }

    private fun check(label: String, r: AccentRoles, background: Long) {
        fun ok(name: String, a: Long, b: Long) = assertTrue("$label $name ${Contrast.ratio(a, b)}", Contrast.ratio(a, b) >= 4.5)
        ok("primary/onPrimary", r.primary, r.onPrimary)
        ok("container", r.primaryContainer, r.onPrimaryContainer)
        ok("primary/background", r.primary, background)
        ok("secondary", r.secondary, r.onSecondary)
        ok("secondaryContainer", r.secondaryContainer, r.onSecondaryContainer)
        ok("tertiary", r.tertiary, r.onTertiary)
        ok("tertiaryContainer", r.tertiaryContainer, r.onTertiaryContainer)
        ok("secondary/background", r.secondary, background)
        ok("tertiary/background", r.tertiary, background)
    }

    @Test
    fun everySeedPassesInLightDarkAndBlack() {
        for (seed in seeds) {
            val tag = ColorMath.formatHex(seed)
            check("$tag light", AccentPalette.forSeed(seed, dark = false, black = false), AccentPalette.LIGHT_BACKGROUND)
            check("$tag dark", AccentPalette.forSeed(seed, dark = true, black = false), AccentPalette.DARK_BACKGROUND)
            check("$tag black", AccentPalette.forSeed(seed, dark = true, black = true), AccentPalette.BLACK_BACKGROUND)
        }
    }

    @Test
    fun aReadableSeedIsKeptAsIs() {
        val navy = 0xFF1A3C8FL
        val light = AccentPalette.forSeed(navy, dark = false, black = false)
        assertEquals(navy, light.primary)
        assertEquals(false, light.adjusted)
    }

    @Test
    fun aHardSeedIsAdjustedAndKeepsItsHue() {
        val yellow = 0xFFFFFF00L
        val light = AccentPalette.forSeed(yellow, dark = false, black = false)
        assertTrue(light.adjusted)
        assertEquals(60.0, ColorMath.toHsl(light.primary).h, 3.0)
        val dark = AccentPalette.forSeed(yellow, dark = true, black = false)
        assertEquals(yellow, dark.primary)
    }

    @Test
    fun hexRoundTrip() {
        for (seed in seeds) assertEquals(seed, ColorMath.parseHex(ColorMath.formatHex(seed)))
        assertEquals("#0A0B0C", ColorMath.formatHex(0xFF0A0B0CL))
        assertEquals(0xFF112233L, ColorMath.parseHex("112233"))
        assertEquals(0xFFAABBCCL, ColorMath.parseHex(" #abc "))
    }

    @Test
    fun invalidHexIsNull() {
        for (bad in listOf(null, "", "#", "#12", "#12345", "#1234567", "#GGGGGG", "red", "#12 456", "##112233")) assertNull("$bad", ColorMath.parseHex(bad))
    }

    @Test
    fun hsvAndHslRoundTripWithinOneStep() {
        for (seed in seeds) {
            val v = ColorMath.toHsv(seed)
            assertEquals(seed, ColorMath.fromHsv(v))
            val l = ColorMath.toHsl(seed)
            assertEquals(seed, ColorMath.fromHsl(l.h, l.s, l.l))
        }
        assertEquals(0xFFFF0000L, ColorMath.fromHsv(0.0, 1.0, 1.0))
        assertEquals(0xFF00FF00L, ColorMath.fromHsv(120.0, 1.0, 1.0))
        assertEquals(0xFF0000FFL, ColorMath.fromHsv(240.0, 1.0, 1.0))
        assertEquals(0xFF000000L, ColorMath.fromHsv(200.0, 0.5, 0.0))
    }

    @Test
    fun customAccentDefaultsAndStoredFallbacks() {
        assertNull(AppSettings().customAccent)
        assertEquals(AccentColor.CUSTOM, AccentColor.fromStored("CUSTOM"))
        assertEquals(AccentColor.DEFAULT, AccentColor.fromStored("MAGENTA"))
        assertEquals(AccentColor.ROSE, AccentColor.fromStored("ROSE"))
    }
}
