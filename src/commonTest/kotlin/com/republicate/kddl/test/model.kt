package com.republicate.kddl.test

import com.github.ajalt.clikt.completion.CompletionCandidates.Path
import com.republicate.kddl.Format
import com.republicate.kddl.KddlProcessor
import com.republicate.kddl.Utils
import com.republicate.kddl.parse
import org.antlr.v4.kotlinruntime.CharStreams
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

class AliasTest {

    @Test
    fun testEnumWithoutAlias() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  status enum('a','b')
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["status"]!!
        assertEquals("enum('a','b')", field.type)
        assertNull(field.alias)
    }

    @Test
    fun testEnumWithAlias() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  mode enum('human','bot') as GameMode
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["mode"]!!
        assertEquals("enum('human','bot')", field.type)
        assertEquals("GameMode", field.alias)
    }

    @Test
    fun testEnumWithAliasAndDefault() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  difficulty enum('easy','medium','hard') as DifficultyLevel = 'medium'
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["difficulty"]!!
        assertEquals("enum('easy','medium','hard')", field.type)
        assertEquals("DifficultyLevel", field.alias)
        assertEquals("medium", field.default)
        assertTrue(field.nonNull)
    }

    @Test
    fun testNullableEnumWithAlias() {
        // Grammar order: type optional? alias? default?
        // So nullable syntax is: enum(...)? as Alias
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  result enum('win','loss')? as GameResult
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["result"]!!
        assertEquals("enum('win','loss')", field.type)
        assertEquals("GameResult", field.alias)
        assertFalse(field.nonNull)
    }

    @Test
    fun testNullableEnumWithAliasAndDefault() {
        // Grammar order: type optional? alias? default?
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  status enum('a','b','c')? as Status = 'a'
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["status"]!!
        assertEquals("enum('a','b','c')", field.type)
        assertEquals("Status", field.alias)
        assertEquals("a", field.default)
        assertFalse(field.nonNull)
    }

    @Test
    fun testMultipleEnumsWithMixedAliases() {
        val ddl = """
            database test {
              schema s {
                table game {
                  *id serial
                  mode enum('human','bot') as GameMode = 'human'
                  status enum('waiting','playing','finished')
                  result enum('win','loss','draw')? as GameResult
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["game"]!!

        val mode = table.fields["mode"]!!
        assertEquals("GameMode", mode.alias)
        assertEquals("human", mode.default)

        val status = table.fields["status"]!!
        assertNull(status.alias)
        assertNull(status.default)

        val result = table.fields["result"]!!
        assertEquals("GameResult", result.alias)
        assertFalse(result.nonNull)
    }

    @Test
    fun testNonEnumFieldHasNoAlias() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  name varchar(50)
                  count integer = 0
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["t"]!!

        assertNull(table.fields["id"]!!.alias)
        assertNull(table.fields["name"]!!.alias)
        assertNull(table.fields["count"]!!.alias)
    }
}
