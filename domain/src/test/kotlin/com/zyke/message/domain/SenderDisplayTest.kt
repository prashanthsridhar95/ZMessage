package com.zyke.message.domain

import com.zyke.message.domain.conversation.SenderDisplay
import com.zyke.message.domain.conversation.SenderHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderDisplayTest {
    private fun name(raw: String) = SenderDisplay.of(raw).name
    private fun verified(raw: String) = SenderDisplay.of(raw).verified

    @Test
    fun everyCategorySuffixIsStrippedWithTheRoutePrefix() {
        for (suffix in listOf("S", "T", "P", "G")) {
            val header = SenderDisplay.parseHeader("VM-SPINNY-$suffix")
            assertEquals(SenderHeader("VM", "SPINNY", suffix[0]), header)
            assertEquals("Spinny", name("VM-SPINNY-$suffix"))
            assertTrue(verified("VM-SPINNY-$suffix"))
        }
    }

    @Test
    fun knownBanksAndBrandsUseTheNameTheRestOfTheAppUses() {
        assertEquals("HDFC Bank", name("AX-HDFCBK-T"))
        assertEquals("HDFC Bank", name("HDFCBK-S"))
        assertEquals("ICICI Bank", name("VM-ICICIT-S"))
        assertEquals("Axis Bank", name("AD-AXISBK-S"))
        assertEquals("Jio", name("AD-JIO-P"))
        assertEquals("Airtel", name("AD-AIRTEL-S"))
        assertEquals("IRCTC", name("JD-IRCTCI-G"))
    }

    @Test
    fun unknownNamesAreTitleCased() {
        assertEquals("Carsew", name("CP-CARSEW-T"))
        assertEquals("Spinny", name("VM-SPINNY-S"))
        assertEquals("Xyz", name("BZ-XYZ-P"))
    }

    @Test
    fun aGroupOfCompanyHeadersIsNotTakenForTheBank() {
        assertEquals("Kotaksec", name("VM-KOTAKSEC-S"))
        assertEquals("Axismf", name("VM-AXISMF-S"))
    }

    @Test
    fun withoutAPrefixTheNameStillResolves() {
        assertEquals(SenderHeader(null, "HDFCBK", 'S'), SenderDisplay.parseHeader("HDFCBK-S"))
        assertEquals("Spinny", name("SPINNY-T"))
        assertTrue(verified("SPINNY-T"))
    }

    @Test
    fun withoutASuffixAStrictCapitalHeaderIsStillRegistered() {
        assertEquals(SenderHeader("AX", "HDFCBK", null), SenderDisplay.parseHeader("AX-HDFCBK"))
        assertEquals("HDFC Bank", name("AX-HDFCBK"))
        assertTrue(verified("VM-ICICIT"))
        assertFalse(verified("Ax-Hdfcbk"))
    }

    @Test
    fun hyphensDigitsAndLettersInsideNames() {
        assertEquals(SenderHeader("VM", "ZEE5", 'S'), SenderDisplay.parseHeader("VM-ZEE5-S"))
        assertEquals("Zee5", name("VM-ZEE5-S"))
        assertEquals("Jio", name("AD-JIO-P"))
        assertEquals(SenderHeader("VK", "AMAZON-IN", 'S'), SenderDisplay.parseHeader("VK-AMAZON-IN-S"))
        assertEquals("Amazon", name("VK-AMAZON-IN-S"))
        assertEquals("Foo Bar", name("VK-FOO-BAR-S"))
        assertEquals("Mycar24", name("VK-MYCAR24-T"))
    }

    @Test
    fun lowercaseHeadersParseButMixedCaseNamesAreNotHeaders() {
        assertEquals(SenderHeader("VM", "SPINNY", 'S'), SenderDisplay.parseHeader("vm-spinny-s"))
        assertEquals("Spinny", name("vm-spinny-s"))
        assertNull(SenderDisplay.parseHeader("Vm-Spinny-S"))
        assertNull(SenderDisplay.parseHeader("Raj-S"))
        assertEquals("Raj-S", name("Raj-S"))
        assertFalse(verified("Raj-S"))
    }

    @Test
    fun numbersShortCodesAndContactsAreNeverVerified() {
        for (raw in listOf("+919876543210", "9876543210", "098765-43210", "56767", "155260", "Mom", "Dr. Sharma", "AIRTEL", "+91 98765 43210", "AB-12", "VM-123456")) {
            assertNull("$raw", SenderDisplay.parseHeader(raw))
            assertFalse(raw, verified(raw))
            assertEquals(raw, name(raw))
        }
    }

    @Test
    fun aBlankSenderHasAPlaceholderNameAndNoMark() {
        assertEquals("Unknown sender", name(""))
        assertEquals("Unknown sender", name("   "))
        assertFalse(verified(""))
    }

    @Test
    fun padding_andOddShapesDoNotThrow() {
        for (raw in listOf("  VM-SPINNY-S  ", "-", "--", "-S", "VM--S", "VM-S", "A-B-C-D-E-F", "-----", "VM-" + "X".repeat(60) + "-S", "नम-नम-S", "VM-SPINNY-S\n")) {
            SenderDisplay.of(raw)
        }
        assertEquals("Spinny", name("  VM-SPINNY-S  "))
        assertNull(SenderDisplay.parseHeader("VM-" + "X".repeat(60) + "-S"))
    }

    @Test
    fun anRbmAgentAddressIsVerifiedAndAFriendlyNameIsNot() {
        val agent = "hdfc_bank_gkjmtq7y_agent@rbm.google.com"
        assertEquals("HDFC Bank", name(agent))
        assertTrue(verified(agent))
        // Once RbmSender has turned the address into a name, nothing says it came from an agent any more.
        assertFalse(verified("HDFC Bank"))
        assertFalse(verified("Mom"))
    }

    @Test
    fun aNicknameWinsForTheNameAndTheMarkFollowsTheHeader() {
        val registered = SenderDisplay.of("AX-HDFCBK-T", nickname = "My bank")
        assertEquals("My bank", registered.name)
        assertTrue(registered.verified)
        val phone = SenderDisplay.of("+919876543210", nickname = "Raj")
        assertEquals("Raj", phone.name)
        assertFalse(phone.verified)
        assertEquals("HDFC Bank", SenderDisplay.of("AX-HDFCBK-T", nickname = "  ").name)
    }

    @Test
    fun aNameFromTheMessageIsUsedOnlyWhenTheHeaderDoesNotNameTheSender() {
        assertEquals("ICICI Bank", SenderDisplay.of("+919876543210", fallbackName = "ICICI Bank").name)
        assertFalse(SenderDisplay.of("+919876543210", fallbackName = "ICICI Bank").verified)
        assertEquals("HDFC Bank", SenderDisplay.of("AX-HDFCBK-T", fallbackName = "ICICI Bank").name)
    }

    @Test
    fun theSpokenFormReadsVerifiedSender() {
        assertEquals("Spinny, verified sender", SenderDisplay.of("VM-SPINNY-S").spoken)
        assertEquals("+919876543210", SenderDisplay.of("+919876543210").spoken)
    }

    @Test
    fun titleCaseSplitsCamelCaseAndUnderscoresAndKeepsAcronyms() {
        assertEquals("My Shop", SenderDisplay.titleCase("MyShop"))
        assertEquals("Shop Zone", SenderDisplay.titleCase("SHOP_ZONE"))
        assertEquals("HDFC Bank", SenderDisplay.titleCase("HdfcBank"))
        assertEquals("SBI Card", SenderDisplay.titleCase("SBI-CARD"))
        assertEquals("Amazon IN", SenderDisplay.titleCase("AMAZON-IN"))
        assertEquals("Zee5", SenderDisplay.titleCase("ZEE5"))
        assertNotNull(SenderDisplay.titleCase("a"))
    }

    @Test
    fun nameAloneForNotifications() {
        assertEquals("HDFC Bank", SenderDisplay.name("AX-HDFCBK-T"))
        assertEquals("+919876543210", SenderDisplay.name("+919876543210"))
    }
}
