package io.github.abooodbah.leanrows

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Reads the source manifest. The merged manifest of the release APK is checked
 * again in CI with aapt2, because a library could add permissions there.
 */
class ManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val TOOLS = "http://schemas.android.com/tools"

    private val manifest: Element by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
    }

    private fun elements(name: String): List<Element> {
        val nodes = manifest.getElementsByTagName(name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private fun Element.children(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun filters(action: String) = elements("intent-filter").filter { filter ->
        filter.children("action").any { it.attr("name") == action }
    }

    @Test
    fun requestsNoPermissions() {
        // Entries marked tools:node="remove" take a library's request out of
        // the merged manifest; CI checks the merged result with aapt2.
        val requested = (elements("uses-permission") + elements("uses-permission-sdk-23"))
            .filter { it.getAttributeNS(TOOLS, "node") != "remove" }
        assertEquals(emptyList<String>(), requested.map { it.attr("name") })
    }

    @Test
    fun opensRowFilesFromOtherApps() {
        val view = filters("android.intent.action.VIEW").flatMap { it.children("data") }
        val types = view.map { it.attr("mimeType") }
        for (type in listOf("text/csv", "text/comma-separated-values", "text/tab-separated-values", "application/x-ndjson")) {
            assertTrue("VIEW filters accept $type: $types", type in types)
        }
        assertTrue("VIEW filters accept content URIs", "content" in view.map { it.attr("scheme") })
        val patterns = view.map { it.attr("pathPattern") }
        for (extension in listOf("csv", "tsv", "jsonl", "ndjson", "log")) {
            assertTrue("VIEW filters match .$extension names: $patterns", ".*\\\\.$extension" in patterns)
        }
    }

    @Test
    fun opensExcelWorkbooksFromOtherApps() {
        val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val view = filters("android.intent.action.VIEW").flatMap { it.children("data") }
        assertTrue("VIEW filters accept $xlsx", xlsx in view.map { it.attr("mimeType") })
        assertTrue("VIEW filters match .xlsx names", ".*\\\\.xlsx" in view.map { it.attr("pathPattern") })
        val send = filters("android.intent.action.SEND").flatMap { it.children("data") }
        assertTrue("SEND filters accept $xlsx", xlsx in send.map { it.attr("mimeType") })
    }

    @Test
    fun sharedRowFilesOpenHere() {
        val types = filters("android.intent.action.SEND").flatMap { it.children("data") }.map { it.attr("mimeType") }
        assertTrue("SEND filters accept text/csv: $types", "text/csv" in types)
        assertTrue("SEND filters accept text/tab-separated-values: $types", "text/tab-separated-values" in types)
    }
}
