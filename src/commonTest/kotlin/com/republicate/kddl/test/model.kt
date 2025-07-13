package com.republicate.kddl.test

import com.github.ajalt.clikt.completion.CompletionCandidates.Path
import com.republicate.kddl.Format
import com.republicate.kddl.KddlProcessor
import com.republicate.kddl.Utils
import kotlin.test.*

class KDDLTest {

    @Test
    fun testPlantuml() = runTest {
        val actual = KddlProcessor("model.kddl", Format.PLANTUML).process()
        val expected = getTestResource("model.plantuml")
        assertEquals(expected, actual)
    }

    @Test
    fun testPostgresql() = runTest {
        val actual = KddlProcessor("model.kddl", Format.POSTGRESQL).process()
        val expected = getTestResource("model.postgresql")
        assertEquals(expected, actual)
    }
}
