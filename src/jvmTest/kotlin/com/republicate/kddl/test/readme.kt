package com.republicate.kddl.test

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The README shows the example model inline, github having no way to include a file. That copy is
 * kept honest here rather than by discipline, which is what let three of them drift apart.
 */
class ReadmeTest {

    @Test
    fun testReadmeQuotesTheExampleVerbatim() {
        val readme = File("README.md")
        assertTrue(readme.exists(), "README.md not found from ${File(".").absolutePath}")
        val lines = readme.readLines()
        val start = lines.indexOf("    <pre>")
        val end = lines.indexOf("    </pre>")
        assertTrue(start >= 0 && start < end, "no <pre> example block in README.md")
        val quoted = lines.subList(start + 1, end).joinToString("\n")
        val model = File("src/commonTest/resources/example.kddl").readText().trimEnd('\n')
        assertEquals(model, quoted, "the README example block and example.kddl have drifted")
    }
}
