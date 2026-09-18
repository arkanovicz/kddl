package com.republicate.kddl.test

import com.republicate.kddl.ASTCondition
import com.republicate.kddl.ReverseEngineer
import com.republicate.kddl.database
import com.republicate.kddl.field
import com.republicate.kddl.guessDatabaseName
import com.republicate.kddl.guessServerName
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

class ServerNameTest {

    @Test
    fun testNamedHost() {
        assertEquals("myaou", guessServerName("jdbc:mysql://root:pw@myaou.example.com:3306/?useSSL=false"))
        assertEquals("jeudego", guessServerName("jdbc:postgresql://jeudego/"))
    }

    @Test
    fun testLoopback() {
        assertEquals("localhost", guessServerName("jdbc:mysql://root:pw@127.0.0.1/?useSSL=false"))
        assertEquals("localhost", guessServerName("jdbc:mysql://localhost:3306/"))
        assertEquals("localhost", guessServerName("jdbc:mysql://[::1]:3306/"))
    }

    @Test
    fun testAddressIsNoName() {
        // an ip address cannot be a kddl identifier, and half of one would be a lie
        assertEquals("unknown", guessServerName("jdbc:mysql://192.168.1.50/"))
        assertEquals("unknown", guessServerName("jdbc:mysql://[2001:db8::1]:3306/"))
    }

    @Test
    fun testNamedDatabaseStillWins() {
        assertEquals("ffg", guessDatabaseName("jdbc:mysql://root:pw@127.0.0.1/ffg?useSSL=false"))
        assertEquals("localhost", guessDatabaseName("jdbc:mysql://root:pw@127.0.0.1/?useSSL=false"))
    }
}
