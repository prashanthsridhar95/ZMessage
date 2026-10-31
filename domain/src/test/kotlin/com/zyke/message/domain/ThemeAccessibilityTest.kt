package com.zyke.message.domain

import com.zyke.message.domain.model.AccentColor
import com.zyke.message.domain.model.AppSettings
import com.zyke.message.domain.model.Contrast
import com.zyke.message.domain.model.ThemeMode
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ThemeAccessibilityTest {
    private val lightBackground = 0xFFFFFBFEL
    private val darkBackground = 0xFF1C1B1FL
    private val blackBackground = 0xFF000000L

    @Test
    fun contrastKnownValues() {
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF336699, 0xFF336699), 0.001)
    }

    @Test
    fun everyAccentMeetsAaInLightDarkAndBlack() {
        for (a in AccentColor.entries) {
            val checks = mapOf(
                "light on-primary" to Contrast.ratio(a.lightPrimary, a.lightOnPrimary),
                "light on background" to Contrast.ratio(a.lightPrimary, lightBackground),
                "dark on-primary" to Contrast.ratio(a.darkPrimary, a.darkOnPrimary),
                "dark on background" to Contrast.ratio(a.darkPrimary, darkBackground),
                "black on-primary" to Contrast.ratio(a.darkPrimary, a.darkOnPrimary),
                "black on background" to Contrast.ratio(a.darkPrimary, blackBackground)
            )
            for ((name, r) in checks) assertTrue("${a.name} $name is $r", r >= Contrast.AA_TEXT)
        }
    }

    @Test
    fun atLeastFiveAccents() = assertTrue(AccentColor.entries.size >= 5)

    @Test
    fun settingsDefaults() {
        val s = AppSettings()
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(AccentColor.DEFAULT, s.accent)
        assertEquals(false, s.hideAmounts)
    }

    @Test
    fun unknownStoredValuesFallBack() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("SOLAR"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.BLACK, ThemeMode.fromStored("BLACK"))
        assertEquals(AccentColor.DEFAULT, AccentColor.fromStored("NOPE"))
        assertEquals(AccentColor.TEAL, AccentColor.fromStored("TEAL"))
    }
}
