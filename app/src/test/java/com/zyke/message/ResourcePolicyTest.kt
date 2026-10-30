package com.zyke.message

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Mistakes that only show up on a phone, and so reached the person twice, caught here instead: a widget the launcher
 * refuses to add, a notification whose text cannot be read, a layout naming something that does not exist.
 */
class ResourcePolicyTest {
    private val main = File("src/main")
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    private fun elements(root: Element): List<Element> = buildList {
        fun walk(node: Node) {
            if (node is Element) add(node)
            for (i in 0 until node.childNodes.length) walk(node.childNodes.item(i))
        }
        walk(root)
    }

    /** Layouts that are inflated as RemoteViews: any layout the code hands to RemoteViews(...), and the one named by the widget. */
    private fun remoteLayouts(): Set<String> {
        val fromCode = File(main, "java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("RemoteViews\\([^)]*R\\.layout\\.(\\w+)").findAll(it.readText()).map { m -> m.groupValues[1] } }
        val fromWidget = File(main, "res/xml").listFiles().orEmpty().flatMap { xml ->
            val text = xml.readText()
            Regex("@layout/(\\w+)").findAll(text).map { it.groupValues[1] }.toList()
        }
        return (fromCode + fromWidget).toSet()
    }

    @Test
    fun remoteViewLayoutsUseOnlyViewsTheSystemAllows() {
        // A widget or notification layout with any other view (a plain View, for one) fails to inflate: "Couldn't add widget".
        val allowed = setOf(
            "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout", "AnalogClock", "Button", "Chronometer", "ImageButton",
            "ImageView", "ProgressBar", "TextClock", "TextView", "ViewFlipper", "ListView", "GridView", "StackView",
            "AdapterViewFlipper", "ViewStub"
        )
        val layouts = remoteLayouts()
        assertTrue("no remote layouts found", layouts.isNotEmpty())
        layouts.forEach { name ->
            val used = elements(parse(File(main, "res/layout/$name.xml"))).map { it.tagName }.toSet()
            assertEquals("$name uses views a widget or notification may not", emptySet<String>(), used - allowed)
        }
    }

    @Test
    fun notificationLayoutsTakeTheirTextColoursFromTheSystem() {
        // Hard-coded or theme colours can match the app and clash with the notification's own background, so the text is unreadable.
        File(main, "res/layout").listFiles().orEmpty().filter { it.name.startsWith("notification_") }.forEach { file ->
            elements(parse(file)).filter { it.tagName == "TextView" }.forEach { text ->
                assertEquals("${file.name} sets its own text colour", "", text.getAttributeNS(android, "textColor"))
                assertTrue("${file.name} must use the notification text style", text.getAttribute("style").contains("TextAppearance.Compat.Notification"))
            }
        }
    }

    @Test
    fun layoutsOnlyNameDrawablesAndColoursThatExist() {
        val colours = File(main, "res/values").listFiles().orEmpty().joinToString("\n") { it.readText() }
        File(main, "res/layout").listFiles().orEmpty().forEach { layout ->
            val text = layout.readText()
            Regex("@drawable/(\\w+)").findAll(text).map { it.groupValues[1] }.forEach { name ->
                val exists = File(main, "res").walkTopDown().any { it.isFile && it.nameWithoutExtension == name && it.parentFile.name.startsWith("drawable") }
                assertTrue("${layout.name} names a drawable that does not exist: $name", exists)
            }
            Regex("@color/(\\w+)").findAll(text).map { it.groupValues[1] }.forEach { name ->
                val exists = "name=\"$name\"" in colours || File(main, "res").walkTopDown().any { it.isFile && it.nameWithoutExtension == name && it.parentFile.name.startsWith("color") }
                assertTrue("${layout.name} names a colour that does not exist: $name", exists)
            }
        }
    }

    @Test
    fun everyComponentInTheManifestHasAClass() {
        val manifest = parse(File(main, "AndroidManifest.xml"))
        val sources = File(main, "java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("(?m)^\\s*(?:\\w+\\s+)*class\\s+(\\w+)").findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        listOf("activity", "service", "receiver").flatMap { tag ->
            val nodes = manifest.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(android, "name") }
        }.forEach { name ->
            assertTrue("$name has no class", name.substringAfterLast('.') in sources)
        }
    }
}
