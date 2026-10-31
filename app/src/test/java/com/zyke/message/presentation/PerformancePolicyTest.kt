package com.zyke.message.presentation

import com.zyke.message.presentation.format.FormatCache
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the things that keep scrolling smooth, which are easy to undo by accident and invisible in a code review. */
class PerformancePolicyTest {

    private fun appRoot(): File = listOf(".", "app").map { File(it) }.first { File(it, "src/main/java").isDirectory }

    private fun presentationFiles(): List<File> =
        File(appRoot(), "src/main/java/com/zyke/message/presentation").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** A lazy list without keys loses scroll position, animations and item state when its data changes. */
    @Test
    fun lazyListItemsAreKeyed() {
        val call = Regex("""(?<![\w.])items(Indexed)?\(""")
        val offenders = presentationFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                if (call.containsMatchIn(code) && "key" !in code) "${file.name}:${index + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("items(...) in a lazy list needs key = { ... }:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    /** The domain models live in another module, so Compose only treats them as stable when told to. */
    @Test
    fun composeCompilerKnowsTheDomainModelsAreStable() {
        val config = File(appRoot(), "compose_compiler_config.txt").readLines().map { it.trim() }
        assertTrue("com.zyke.message.domain.**" in config)
        val build = File(appRoot(), "build.gradle.kts").readText()
        assertTrue("stabilityConfigurationFile" in build)
        assertTrue("compose_compiler_config.txt" in build)
    }

    /** Every rule in the baseline profile is well formed and names a package that still exists. */
    @Test
    fun baselineProfileRulesAreWellFormedAndCurrent() {
        val file = File(appRoot(), "src/main/baseline-prof.txt")
        val rules = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("baseline profile is empty", rules.isNotEmpty())
        val shape = Regex("""^(?:[HSP]{1,3})?L[\w/$*]+(?:;|;->[\w$*<>]+\(\*\*\)\*\*)$""")
        val sourceRoot = File(appRoot(), "src/main/java")
        val domainRoot = File(appRoot(), "../domain/src/main/kotlin")
        rules.forEach { rule ->
            assertTrue("malformed baseline rule: $rule", shape.matches(rule))
            val descriptor = rule.substringAfter('L').substringBefore(';')
            val packagePath = descriptor.substringBeforeLast('/').replace(Regex("/?\\*+.*$"), "")
            val exists = listOf(sourceRoot, domainRoot).any { File(it, packagePath).exists() }
            assertTrue("baseline rule names a package that no longer exists: $rule", exists)
        }
    }

    @Test
    fun dateFormattersAreBuiltOncePerPatternAndLocale() {
        val first = FormatCache.pattern("d MMM", Locale.US)
        assertSame(first, FormatCache.pattern("d MMM", Locale.US))
        assertNotSame(first, FormatCache.pattern("d MMM", Locale.FRANCE))
        assertNotSame(first, FormatCache.pattern("MMM", Locale.US))
    }

    @Test
    fun numberFormattersKeepTheirFractionDigits() {
        assertEquals("1,234", FormatCache.number(Locale.US, 0, 2).format(1234))
        assertEquals("1,234.50", FormatCache.number(Locale.US, 2, 2).format(1234.5))
        // Asked again with other digits, the first answer is not disturbed.
        assertEquals("1,234", FormatCache.number(Locale.US, 0, 2).format(1234))
    }
}
