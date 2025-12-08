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

class FieldModifiersTest {

    @Test
    fun testPrimaryKey() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  name varchar(50)
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["t"]!!
        assertTrue(table.fields["id"]!!.primaryKey)
        assertFalse(table.fields["name"]!!.primaryKey)
    }

    @Test
    fun testUniqueField() {
        val ddl = """
            database test {
              schema s {
                table user {
                  *id serial
                  !email varchar(100)
                  name varchar(50)
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["user"]!!
        assertTrue(table.fields["email"]!!.unique)
        assertFalse(table.fields["name"]!!.unique)
    }

    @Test
    fun testIndexedField() {
        val ddl = """
            database test {
              schema s {
                table event {
                  *id serial
                  +timestamp timestamp
                  data text
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["event"]!!
        assertTrue(table.fields["timestamp"]!!.indexed)
        assertFalse(table.fields["data"]!!.indexed)
    }

    @Test
    fun testNullableField() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  required_name varchar(50)
                  optional_name varchar(50)?
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["t"]!!
        assertTrue(table.fields["required_name"]!!.nonNull)
        assertFalse(table.fields["optional_name"]!!.nonNull)
    }
}

class DefaultValuesTest {

    @Test
    fun testStringDefault() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  status varchar(20) = 'active'
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["status"]!!
        assertEquals("active", field.default)
    }

    @Test
    fun testIntegerDefault() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  count integer = 0
                  priority integer = 5
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["t"]!!
        // Numbers are parsed as Double
        assertEquals(0.0, table.fields["count"]!!.default)
        assertEquals(5.0, table.fields["priority"]!!.default)
    }

    @Test
    fun testBooleanDefault() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  active boolean = true
                  deleted boolean = false
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["t"]!!
        assertEquals(true, table.fields["active"]!!.default)
        assertEquals(false, table.fields["deleted"]!!.default)
    }

    @Test
    fun testFunctionDefault() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  created timestamp = now()
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["created"]!!
        assertEquals("now()", field.default)
    }

    @Test
    fun testNullDefault() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  deleted_at timestamp? = null
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["deleted_at"]!!
        // NULL keyword results in Kotlin null
        assertNull(field.default)
        assertFalse(field.nonNull)
    }
}

class ForeignKeyTest {

    @Test
    fun testSimpleForeignKey() {
        val ddl = """
            database test {
              schema s {
                table author {
                  *author_id serial
                  name varchar(100)
                }
                table book {
                  *book_id serial
                  title varchar(200)
                  author_id -> author
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val book = db.schemas["s"]!!.tables["book"]!!
        val authorField = book.fields["author_id"]!!
        assertTrue(authorField.isLinkField())
        val fk = authorField.getForeignKeys().first()
        assertEquals("author", fk.towards.name)
    }

    @Test
    fun testNullableForeignKey() {
        val ddl = """
            database test {
              schema s {
                table category {
                  *category_id serial
                  name varchar(50)
                }
                table item {
                  *item_id serial
                  category_id -> category?
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val item = db.schemas["s"]!!.tables["item"]!!
        val catField = item.fields["category_id"]!!
        assertFalse(catField.nonNull)
    }

    @Test
    fun testCascadeForeignKey() {
        val ddl = """
            database test {
              schema s {
                table parent {
                  *parent_id serial
                }
                table child {
                  *child_id serial
                  parent_id -> parent cascade
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val child = db.schemas["s"]!!.tables["child"]!!
        val fk = child.fields["parent_id"]!!.getForeignKeys().first()
        assertTrue(fk.cascade)
    }
}

class TableInheritanceTest {

    @Test
    fun testSimpleInheritance() {
        val ddl = """
            database test {
              schema s {
                table base {
                  *id serial
                  name varchar(50)
                }
                table derived : base {
                  extra varchar(100)
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val derived = db.schemas["s"]!!.tables["derived"]!!
        assertNotNull(derived.parent)
        assertEquals("base", derived.parent!!.name)
    }

    @Test
    fun testInheritedFieldAccess() {
        val ddl = """
            database test {
              schema s {
                table base {
                  *id serial
                  name varchar(50)
                }
                table derived : base {
                  extra varchar(100)
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val derived = db.schemas["s"]!!.tables["derived"]!!
        // Direct field
        assertNotNull(derived.fields["extra"])
        // Inherited field via getMaybeInheritedField
        assertNotNull(derived.getMaybeInheritedField("name"))
        assertNotNull(derived.getMaybeInheritedField("id"))
    }
}

class LinkTest {

    @Test
    fun testManyToManyLink() {
        val ddl = """
            database test {
              schema s {
                table student {
                  *student_id serial
                  name varchar(100)
                }
                table course {
                  *course_id serial
                  title varchar(200)
                }
                student *--* course
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val schema = db.schemas["s"]!!
        // Join table should be created
        assertTrue(schema.tables.containsKey("student_course"))
        val joinTable = schema.tables["student_course"]!!
        assertEquals(2, joinTable.foreignKeys.size)
    }

    @Test
    fun testOneToManyLink() {
        val ddl = """
            database test {
              schema s {
                table department {
                  *department_id serial
                  name varchar(100)
                }
                table employee {
                  *employee_id serial
                  name varchar(100)
                }
                department <--* employee
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val employee = db.schemas["s"]!!.tables["employee"]!!
        // Should have FK to department
        assertTrue(employee.foreignKeys.isNotEmpty())
        assertEquals("department", employee.foreignKeys.first().towards.name)
    }
}

class TypesTest {

    @Test
    fun testNumericPrecision() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  price numeric(10,2)
                  quantity numeric(5)
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["t"]!!
        assertEquals("numeric(10,2)", table.fields["price"]!!.type)
        assertEquals("numeric(5)", table.fields["quantity"]!!.type)
    }

    @Test
    fun testVarcharWidth() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  short_name varchar(10)
                  long_name varchar(255)
                  unlimited varchar
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["t"]!!
        assertEquals("varchar(10)", table.fields["short_name"]!!.type)
        assertEquals("varchar(255)", table.fields["long_name"]!!.type)
        assertEquals("varchar", table.fields["unlimited"]!!.type)
    }

    @Test
    fun testTimestampPrecision() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  created timestamp
                  updated timestamp(3)
                  with_tz timestamptz(6)
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["t"]!!
        assertEquals("timestamp", table.fields["created"]!!.type)
        assertEquals("timestamp(3)", table.fields["updated"]!!.type)
        assertEquals("timestamptz(6)", table.fields["with_tz"]!!.type)
    }

    @Test
    fun testSpecialTypes() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id uuid
                  data json
                  bits varbit(8)
                  duration interval
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val table = db.schemas["s"]!!.tables["t"]!!
        assertEquals("uuid", table.fields["id"]!!.type)
        assertEquals("json", table.fields["data"]!!.type)
        assertEquals("varbit(8)", table.fields["bits"]!!.type)
        assertEquals("interval", table.fields["duration"]!!.type)
    }
}

class DatabaseOptionsTest {

    @Test
    fun testDatabaseOption() {
        val ddl = """
            database test {
              option version = '1.0'
              schema s {
                table t {
                  *id serial
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        // Option value includes quotes from the STRING token
        assertEquals("'1.0'", db.options["version"])
    }
}
