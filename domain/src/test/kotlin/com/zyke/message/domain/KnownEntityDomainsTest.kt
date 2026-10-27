package com.zyke.message.domain

import com.zyke.message.domain.classification.KnownEntityDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnownEntityDomainsTest {
    @Test
    fun knownNamesMapToTheirSite_andUnknownOnesDoNot() {
        assertEquals("swiggy.com", KnownEntityDomains.domainFor("SWIGGY INSTAMART"))
        assertEquals("play.google.com", KnownEntityDomains.domainFor("Google Play"))
        assertEquals("hdfcbank.com", KnownEntityDomains.domainFor("AD-HDFCBK HDFC Bank"))
        assertNull(KnownEntityDomains.domainFor("Ramesh Kumar"))
        // Short keywords only match whole words, so "ola" does not match "Coimbatore Dola Stores".
        assertNull(KnownEntityDomains.domainFor("Dolafy Stores"))
    }
}
