package com.zyke.message.domain

import com.zyke.message.domain.logo.LogoEntry
import com.zyke.message.domain.logo.LogoList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The logo list: brand keys, web addresses, the shared file format, and which list wins. */
class LogoListTest {
    private val taco = LogoEntry("taco bell", "https://logos.example.com/taco-bell.png")
    private val mrDiy = LogoEntry("mr diy", "https://cdn.example.org/img/mrdiy.png?w=128")

    // ----- keys

    @Test
    fun aKeyIsTheNormalisedName_soSpellingsOfOneBrandAreOneKey() {
        assertEquals("taco bell", LogoList.keyOf("Taco Bell"))
        assertEquals("taco bell", LogoList.keyOf("  TACO   BELL. "))
        assertEquals("mr diy", LogoList.keyOf("Mr. Diy"))
        assertEquals("acme pvt ltd", LogoList.keyOf("ACME Private Limited"))
        assertEquals("7 eleven", LogoList.keyOf("7 Eleven"))
    }

    @Test
    fun anythingThatCouldIdentifyAPersonOrAnAccountHasNoKey() {
        assertNull("a UPI id", LogoList.keyOf("9876543210@ybl"))
        assertNull("a UPI id of a shop", LogoList.keyOf("tacobell@okhdfcbank"))
        assertNull("an account number", LogoList.keyOf("Acct 123456789"))
        assertNull("a card in the name", LogoList.keyOf("Shop XX1234"))
        assertNull("nothing but digits", LogoList.keyOf("5551234"))
        assertNull("blank", LogoList.keyOf("   "))
        assertNull("punctuation only", LogoList.keyOf("--**--"))
    }

    // ----- addresses

    @Test
    fun onlyAPublicHttpsAddressIsAccepted() {
        assertEquals("https://logos.example.com/a.png", LogoList.cleanUrl("  https://LOGOS.example.com/a.png "))
        assertEquals("https://x.example.com/a.png?w=1&h=2", LogoList.cleanUrl("https://x.example.com/a.png?w=1&h=2"))
        for (bad in listOf(
            "http://logos.example.com/a.png", "data:image/png;base64,AAAA", "ftp://x.example.com/a.png", "//x.example.com/a.png",
            "https://user:pw@x.example.com/a.png", "https://x.example.com:8443/a.png", "https://x.example.com", "https://x.example.com/a.png#frag",
            "https://192.168.0.1/a.png", "https://10.0.0.1/a.png", "https://localhost/a.png", "https://printer.local/a.png", "https://intranet/a.png",
            "https://[::1]/a.png", "https://x.example.com/a b.png", "https://x.example.com/é.png", "https://-bad.example.com/a.png", "",
            "https://x.example.com/" + "a".repeat(LogoList.MAX_URL_LENGTH)
        )) assertNull("should be refused: $bad", LogoList.cleanUrl(bad))
    }

    @Test
    fun theHostIsTakenFromTheCleanedAddress() {
        assertEquals("logos.example.com", LogoList.hostOf("https://Logos.Example.com/a/b.png?x=1"))
        assertNull(LogoList.hostOf("http://logos.example.com/a.png"))
        assertEquals(setOf("cdn.example.org", "logos.example.com"), LogoList.hosts(listOf(taco, mrDiy)))
    }

    // ----- file format

    @Test
    fun theExportIsTheDocumentedFormat_withOnlyKeyAndAddress() {
        val json = LogoList.toJson(listOf(mrDiy, taco))
        assertEquals(
            """
            {
              "version": 1,
              "logos": [
                {"key": "mr diy", "url": "https://cdn.example.org/img/mrdiy.png?w=128"},
                {"key": "taco bell", "url": "https://logos.example.com/taco-bell.png"}
              ]
            }
            """.trimIndent() + "\n",
            json
        )
        // Nothing but those two fields is ever written.
        assertEquals(setOf("version", "logos", "key", "url"), Regex("\"([a-z]+)\":").findAll(json).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun anEmptyListExportsAsAValidEmptyFile() {
        val json = LogoList.toJson(emptyList())
        assertEquals("{\n  \"version\": 1,\n  \"logos\": []\n}\n", json)
        val parsed = LogoList.parse(json)
        assertNotNull(parsed)
        assertTrue(parsed!!.entries.isEmpty())
        assertEquals(0, parsed.rejected)
    }

    @Test
    fun whatIsExportedReadsBackTheSame() {
        val parsed = LogoList.parse(LogoList.toJson(listOf(taco, mrDiy)))!!
        assertEquals(listOf(mrDiy, taco), parsed.entries)
        assertEquals(0, parsed.rejected)
    }

    @Test
    fun theExportLeavesOutWhatIsNotClean_soNothingPrivateCanBeShared() {
        val json = LogoList.toJson(
            listOf(
                taco, LogoEntry("9876543210@ybl", "https://x.example.com/a.png"), LogoEntry("Taco Bell", "https://x.example.com/b.png"),
                LogoEntry("shop", "http://x.example.com/c.png"), LogoEntry("other", "data:image/png;base64,AAAA")
            )
        )
        assertEquals(listOf(taco), LogoList.parse(json)!!.entries)
        assertTrue("9876543210" !in json && "data:" !in json && "http://" !in json)
    }

    @Test
    fun aPastedFileIsReadStrictly() {
        val text = """
            {"version":1,"logos":[
              {"key":"taco bell","url":"https://logos.example.com/taco-bell.png"},
              {"key":"Not Canonical","url":"https://logos.example.com/x.png"},
              {"key":"bad url","url":"http://logos.example.com/x.png"},
              {"key":"no url"},
              "nonsense",
              {"key":"taco bell","url":"https://logos.example.com/newer.png"}
            ]}
        """.trimIndent()
        val parsed = LogoList.parse(text)!!
        assertEquals("a key given twice keeps the later address", listOf(LogoEntry("taco bell", "https://logos.example.com/newer.png")), parsed.entries)
        assertEquals(4, parsed.rejected)
    }

    @Test
    fun textThatIsNotTheFormatIsRefusedWithoutThrowing() {
        for (bad in listOf(
            "", "nope", "[]", "{}", """{"version":2,"logos":[]}""", """{"version":1}""", """{"version":1,"logos":{}}""",
            """{"version":"1","logos":[]}""", """{"version":1,"logos":[]} trailing""", """{"version":1,"logos":[""", """{"version":1.5,"logos":[]}""",
            "[".repeat(50) + "]".repeat(50), "{\"version\":1,\"logos\":[{\"key\":\"a\u0001b\"}]}"
        )) assertNull("should be refused: $bad", LogoList.parse(bad))
        assertNull(LogoList.parse(" ".repeat(LogoList.MAX_TEXT + 1)))
    }

    @Test
    fun escapesAndUnicodeKeysSurviveTheRoundTrip() {
        val entry = LogoEntry("café bar", "https://x.example.com/q?a=\"1\"&b=\\2")
        // A quote or backslash in an address is not clean (it would need escaping in the file), so it is left out rather than written.
        assertEquals("https://x.example.com/q?a=\"1\"&b=\\2", LogoList.cleanUrl(entry.url))
        val parsed = LogoList.parse(LogoList.toJson(listOf(entry)))!!
        assertEquals(listOf(entry), parsed.entries)
        assertEquals("é", (LogoList.parse("""{"version":1,"logos":[{"key":"café","url":"https://x.example.com/a.png"}]}""")!!.entries.single().key.last()).toString())
    }

    // ----- precedence

    @Test
    fun whenTwoListsNameTheSameBrand_theSecondOneWins() {
        val bundled = listOf(taco, LogoEntry("kfc", "https://logos.example.com/kfc.png"))
        val own = listOf(LogoEntry("taco bell", "https://mine.example.net/tb.png"))
        val merged = LogoList.merge(bundled, own)
        assertEquals(listOf(LogoEntry("kfc", "https://logos.example.com/kfc.png"), LogoEntry("taco bell", "https://mine.example.net/tb.png")), merged)
        // The other order is the other answer, and the inputs are not touched.
        assertEquals("https://logos.example.com/taco-bell.png", LogoList.table(LogoList.merge(own, bundled))["taco bell"])
        assertEquals(taco, bundled.first())
    }

    @Test
    fun mergeIsSortedAndDropsWhatIsNotClean() {
        val merged = LogoList.merge(listOf(taco, LogoEntry("a@b", "https://x.example.com/a.png")), listOf(LogoEntry("zed", "http://x.example.com/z.png"), mrDiy))
        assertEquals(listOf("mr diy", "taco bell"), merged.map { it.key })
    }

    @Test
    fun aBrandIsFoundByTheNameShownOrTheNameInTheMessage_unlessTurnedOff() {
        val table = LogoList.table(listOf(taco))
        assertEquals("Taco Bell" to taco.url, LogoList.resolve(listOf("Taco Bell"), table) { false })
        // The nickname leads, the name in the message is the second try.
        assertEquals("TACO BELL." to taco.url, LogoList.resolve(listOf("My lunch place", "TACO BELL."), table) { false })
        assertEquals("a name not in the list finds nothing", null, LogoList.resolve(listOf("Somewhere"), table) { false })
        assertNull("turned off for that name", LogoList.resolve(listOf("Taco Bell"), table) { it == "Taco Bell" })
        assertNull("an empty list finds nothing", LogoList.resolve(listOf("Taco Bell"), emptyMap()) { false })
    }
}
