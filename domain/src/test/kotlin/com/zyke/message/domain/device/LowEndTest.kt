package com.zyke.message.domain.device

import com.zyke.message.domain.model.LiteMode
import com.zyke.message.domain.model.LowEndRules
import com.zyke.message.domain.model.PerformanceMode
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class LowEndTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun automaticFollowsTheDetection() {
        assertTrue(LiteMode.resolve(PerformanceMode.AUTOMATIC, true))
        assertFalse(LiteMode.resolve(PerformanceMode.AUTOMATIC, false))
    }

    @Test
    fun aChoiceBeatsTheDetection() {
        assertTrue(LiteMode.resolve(PerformanceMode.LITE, false))
        assertFalse(LiteMode.resolve(PerformanceMode.FULL, true))
    }

    @Test
    fun storedModeFallsBackToAutomatic() {
        assertEquals(PerformanceMode.AUTOMATIC, PerformanceMode.fromStored(null))
        assertEquals(PerformanceMode.AUTOMATIC, PerformanceMode.fromStored("nonsense"))
        PerformanceMode.entries.forEach { assertEquals(it, PerformanceMode.fromStored(it.name)) }
    }

    @Test
    fun lowRamFlagOrSmallMemoryIsLowEnd() {
        assertTrue(LowEndRules.isLowEnd(true, 8 * gb, 512))
        assertTrue(LowEndRules.isLowEnd(false, 3 * gb, 256))
        assertTrue(LowEndRules.isLowEnd(false, 2 * gb, 0))
        assertTrue(LowEndRules.isLowEnd(false, 6 * gb, 192))
    }

    @Test
    fun roomyPhonesAndUnknownValuesAreNotLowEnd() {
        assertFalse(LowEndRules.isLowEnd(false, 4 * gb, 256))
        assertFalse(LowEndRules.isLowEnd(false, 8 * gb, 384))
        assertFalse(LowEndRules.isLowEnd(false, 0, 0))
        assertFalse(LowEndRules.isLowEnd(false, -1, -1))
    }
}

class DeviceProfileTest {
    @Test
    fun presetsAreWhatTheyAreCalled() {
        assertEquals(360 to 745, DeviceProfile.REDMI_9A.widthDp to DeviceProfile.REDMI_9A.heightDp)
        assertEquals(1.15f, DeviceProfile.REDMI_9A.fontScale, 0f)
        assertEquals(393 to 830, DeviceProfile.REDMI_NOTE.widthDp to DeviceProfile.REDMI_NOTE.heightDp)
        assertEquals(320, DeviceProfile.SMALL_320.widthDp)
        assertEquals(3, DeviceProfile.PRESETS.size)
        assertEquals(DeviceProfile.PRESETS.size, DeviceProfile.PRESETS.map { it.id }.toSet().size)
    }

    @Test
    fun offChangesNothing() {
        assertTrue(DeviceProfile.OFF.isIdentity)
        assertFalse(DeviceProfile.REDMI_NOTE.isIdentity)
        assertFalse(DeviceProfile.custom(1.3f, 1f).isIdentity)
        assertEquals("off", DeviceProfile.OFF.summary())
    }

    @Test
    fun storingAndReadingBackKeepsTheProfile() {
        (DeviceProfile.PRESETS + DeviceProfile.OFF + DeviceProfile.custom(1.15f, 1.5f)).forEach {
            assertEquals(it, DeviceProfile.decode(DeviceProfile.encode(it)))
        }
    }

    @Test
    fun malformedStorageIsOff() {
        listOf(null, "", "x", "a|b|c|d|e", "custom|-1|0|1.0|1.0", "custom|1|2|3").forEach {
            assertEquals(it.toString(), DeviceProfile.OFF, DeviceProfile.decode(it))
        }
    }

    @Test
    fun summaryNamesWhatChanges() {
        assertEquals("360×745 dp, font ×1.15", DeviceProfile.REDMI_9A.summary())
        assertEquals("density ×1.3, font ×1.5", DeviceProfile.custom(1.3f, 1.5f).summary())
    }
}

class ScreenClassTest {
    @Test
    fun thresholds() {
        assertTrue(ScreenClass(320, 700, 1f).isCompact)
        assertFalse(ScreenClass(360, 700, 1f).isCompact)
        assertTrue(ScreenClass(393, 600, 1f).isShort)
        assertFalse(ScreenClass(393, 640, 1f).isShort)
        assertTrue(ScreenClass(393, 800, 1.3f).isLargeText)
    }

    @Test
    fun rowsStackWhenNarrowOrLargeTextOnAPhone() {
        assertTrue(ScreenClass(320, 800, 1f).stackRows)
        assertTrue(ScreenClass(393, 800, 1.3f).stackRows)
        assertFalse(ScreenClass(393, 800, 1.15f).stackRows)
        assertFalse(ScreenClass(600, 900, 1.5f).stackRows)
    }

    @Test
    fun chromeTextIsCappedButNeverShrunk() {
        assertEquals(1.3f, ScreenClass.chromeFontScale(1.6f), 0f)
        assertEquals(1.15f, ScreenClass.chromeFontScale(1.15f), 0f)
        assertEquals(0.85f, ScreenClass.chromeFontScale(0.85f), 0f)
    }
}
