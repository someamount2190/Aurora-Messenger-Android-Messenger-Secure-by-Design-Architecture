package com.aura.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every translatable English string must exist in each translation with the same format
 * placeholders, so a new English-only string (or a dropped %1$s) fails the build.
 */
class TranslationParityTest {

    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val translations = listOf("values-ru")

    @Test
    fun translationsCoverEveryEnglishString() {
        val english = load("values")
        for (dir in translations) {
            val translated = load(dir)
            assertEquals("$dir is missing keys", emptySet<String>(), english.keys - translated.keys)
            assertEquals("$dir has keys English lacks", emptySet<String>(), translated.keys - english.keys)
        }
    }

    @Test
    fun placeholdersMatchEnglish() {
        val english = load("values")
        for (dir in translations) {
            val translated = load(dir)
            for ((key, text) in english) {
                val other = translated[key] ?: continue
                assertEquals("$dir/$key placeholders", placeholders(text), placeholders(other))
            }
        }
    }

    @Test
    fun russianPluralsCoverRussianQuantities() {
        val plurals = parse("values-ru").getElementsByTagName("plurals")
        assertTrue(plurals.length > 0)
        for (i in 0 until plurals.length) {
            val p = plurals.item(i) as Element
            val items = p.getElementsByTagName("item")
            val quantities = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
            assertTrue("${p.getAttribute("name")} lacks one/few/many/other: $quantities",
                quantities.containsAll(listOf("one", "few", "many", "other")))
        }
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""").findAll(text).map { it.value }.sorted().toList()

    /** name -> text (plurals flattened to all their items) for translatable entries. */
    private fun load(dir: String): Map<String, String> {
        val doc = parse(dir)
        val out = mutableMapOf<String, String>()
        for (tag in listOf("string", "plurals")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                if (e.getAttribute("translatable") == "false") continue
                out[e.getAttribute("name")] = if (tag == "string") e.textContent
                    else e.getElementsByTagName("item").item(0).textContent
            }
        }
        return out
    }

    private fun parse(dir: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
}
