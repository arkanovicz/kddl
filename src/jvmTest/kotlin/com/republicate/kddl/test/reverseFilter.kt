package com.republicate.kddl.test

import com.republicate.kddl.ASTCondition
import com.republicate.kddl.ReverseEngineer
import com.republicate.kddl.database
import com.republicate.kddl.field
import com.republicate.kddl.schema
import com.republicate.kddl.table
import kotlin.test.*

class ReverseFilterConditionTest {

    private val table = run {
        lateinit var t: com.republicate.kddl.ASTTable
        database("test") {
            schema("s") {
                t = table("delegation") {
                    field("removed_at", "timestamp", nonNull = false)
                    field("active", "boolean")
                }
            }
        }
        t
    }

    private fun parse(filter: String) = ReverseEngineer.parseFilterCondition(filter, table)

    @Test
    fun testIsNull() {
        val cond = parse("(removed_at IS NULL)")
        assertNotNull(cond)
        assertEquals("removed_at", cond.field.name)
        assertEquals(ASTCondition.Op.IS_NULL, cond.op)
    }

    @Test
    fun testIsNotNull() {
        assertEquals(ASTCondition.Op.IS_NOT_NULL, parse("removed_at IS NOT NULL")?.op)
    }

    @Test
    fun testBoolean() {
        assertEquals(ASTCondition.Op.IS_TRUE, parse("active")?.op)
        assertEquals(ASTCondition.Op.IS_FALSE, parse("(NOT active)")?.op)
    }

    @Test
    fun testQuotedIdentifier() {
        assertEquals("removed_at", parse("(\"removed_at\" IS NULL)")?.field?.name)
    }

    @Test
    fun testUnsupportedConditionsRejected() {
        assertNull(parse("(active = false)"))
        assertNull(parse("(removed_at IS NULL) AND (active)"))
        assertNull(parse("unknown_col IS NULL"))
    }
}
