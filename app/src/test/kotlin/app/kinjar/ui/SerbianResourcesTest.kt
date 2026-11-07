package app.kinjar.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class SerbianResourcesTest {
    private val res = File("src/main/res")

    private fun strings(folder: String): Map<String, String> =
        File(res, folder).listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent }

    @Test fun `Serbian lives in one folder that normalized locales resolve to`() {
        val serbianFolders = res.listFiles().orEmpty().filter { it.listFiles().orEmpty().any { file -> file.extension == "xml" } }
            .map { it.name }.filter { it.startsWith("values-sr") || it.startsWith("values-b+sr") }
        assertEquals(listOf("values-b+sr+Latn"), serbianFolders)
    }

    @Test fun `Serbian Latin translates all strings and preserves format arguments`() {
        val english = strings("values")
        val serbian = strings("values-b+sr+Latn")
        assertEquals(english.keys, serbian.keys)
        val arguments = Regex("%([0-9]+\\$)?[dsf]")
        english.forEach { (key, value) ->
            assertEquals(key, arguments.findAll(value).map { it.value }.sorted().toList(),
                arguments.findAll(serbian.getValue(key)).map { it.value }.sorted().toList())
        }
        assertFalse(serbian.values.any { text -> text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC } })
    }
}
