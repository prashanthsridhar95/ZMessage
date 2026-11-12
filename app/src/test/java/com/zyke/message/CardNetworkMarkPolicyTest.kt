package com.zyke.message

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The card network marks are vector drawables made for the app: they must be valid for aapt2 (a plain vector of paths), never
 * fetched, one per network the card face can draw, and named as trademarks of their owners in the legal files.
 */
class CardNetworkMarkPolicyTest {
    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }
    private fun repoRoot(): File = appRoot().canonicalFile.let { if (it.name == "app") it.parentFile else it }
    private val android = "http://schemas.android.com/apk/res/android"
    private val networks = listOf("visa", "mastercard", "amex", "rupay", "diners")

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement

    @Test
    fun everyNetworkHasAValidVectorMadeOfPlainPaths() {
        networks.forEach { n ->
            val file = File(appRoot(), "src/main/res/drawable/network_$n.xml")
            assertTrue("missing ${file.name}", file.isFile)
            val root = parse(file)
            assertEquals("vector", root.tagName)
            val vw = root.getAttributeNS(android, "viewportWidth").toFloat()
            val vh = root.getAttributeNS(android, "viewportHeight").toFloat()
            assertTrue(vw > 0 && vh > 0)
            assertEquals("${file.name} size matches its viewport", "${vw.toInt()}dp", root.getAttributeNS(android, "width"))
            assertEquals("${file.name} size matches its viewport", "${vh.toInt()}dp", root.getAttributeNS(android, "height"))
            val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
            assertTrue(children.isNotEmpty())
            val allowed = setOf("pathData", "fillColor", "strokeColor", "strokeWidth", "strokeLineCap", "strokeLineJoin")
            children.forEach { path ->
                assertEquals("${file.name}: only paths", "path", path.tagName)
                val names = (0 until path.attributes.length).map { path.attributes.item(it).localName }
                assertEquals("${file.name}: attributes aapt2 may not know", emptySet<String>(), names.toSet() - allowed)
                val data = path.getAttributeNS(android, "pathData")
                assertTrue("${file.name}: empty path", data.isNotBlank())
                assertTrue("${file.name}: only path commands", Regex("[MLCHVZ0-9 ,.\\-]+").matches(data))
                listOf("fillColor", "strokeColor").forEach { a ->
                    val c = path.getAttributeNS(android, a)
                    assertTrue("${file.name}: $a must be a literal brand colour", c.isEmpty() || Regex("#[0-9A-Fa-f]{6}").matches(c))
                }
            }
            assertFalse("a mark is never fetched", Regex("https?://(?!schemas\\.android\\.com)").containsMatchIn(file.readText()))
        }
    }

    @Test
    fun theCardFaceDrawsEachNetworkFromItsDrawable_andFetchesNothing() {
        val face = File(appRoot(), "src/main/java/com/zyke/message/presentation/common/CardFace.kt").readText()
        networks.forEach { assertTrue("CardFace does not use network_$it", "R.drawable.network_$it" in face) }
        assertFalse(Regex("""https?://""").containsMatchIn(face))
        // The marks are still, and the face adds no looping animation of its own.
        assertFalse("rememberInfiniteTransition" in face)
        // Each box in the file is the box the face lays out.
        Regex("""MarkSpec\(R\.drawable\.network_(\w+), (\d+), (\d+),""").findAll(face).forEach { m ->
            val root = parse(File(appRoot(), "src/main/res/drawable/network_${m.groupValues[1]}.xml"))
            assertEquals(m.groupValues[2], root.getAttributeNS(android, "viewportWidth").toFloat().toInt().toString())
            assertEquals(m.groupValues[3], root.getAttributeNS(android, "viewportHeight").toFloat().toInt().toString())
        }
    }

    @Test
    fun theLegalFilesNameTheMarksAsTheirOwners() {
        val notes = File(repoRoot(), "docs/LEGAL_NOTES.md").readText()
        val notice = File(repoRoot(), "NOTICE").readText()
        listOf("Visa", "Mastercard", "American Express", "RuPay", "Diners Club").forEach {
            assertTrue("$it missing from NOTICE", it in notice)
            assertTrue("$it missing from LEGAL_NOTES.md", it in notes)
        }
        assertTrue("trademarks" in notice && "trademarks" in notes)
        assertTrue("identification" in notes || "tell which card is which" in notice)
    }
}
