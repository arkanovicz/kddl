package com.republicate.kddl.test

import com.github.ajalt.clikt.completion.CompletionCandidates.Path
import com.republicate.kddl.Format
import com.republicate.kddl.KddlProcessor
import com.republicate.kddl.Utils
import kotlin.test.*

class KDDLTest {

    @Test
    fun testPlantuml() = runTest {
        val actual = KddlProcessor("model.kddl", Format.PLANTUML, fromResource = true).process()
        val expected = getTestResource("model.plantuml")
        assertEquals(expected, actual)
    }

    @Test
    fun testPostgresql() = runTest {
        val actual = KddlProcessor("model.kddl", Format.POSTGRESQL, fromResource = true).process()
        val expected = getTestResource("model.postgresql")
        assertEquals(expected, actual)
    }

    @Test
    fun testTimestampTypes() = runTest {
        val actual = KddlProcessor("types.kddl", Format.POSTGRESQL, fromResource = true).process()
        val expected = getTestResource("types.postgresql")
        assertEquals(expected, actual)
    }
}
