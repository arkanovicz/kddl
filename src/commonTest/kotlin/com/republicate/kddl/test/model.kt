package com.republicate.kddl.test

import com.github.ajalt.clikt.completion.CompletionCandidates.Path
import com.republicate.kddl.FieldType
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
        val t = field.type as FieldType.InlineEnum
        assertEquals(listOf("a", "b"), t.values)
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
        val t = field.type as FieldType.InlineEnum
        assertEquals(listOf("human", "bot"), t.values)
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
        val t = field.type as FieldType.InlineEnum
        assertEquals(listOf("easy", "medium", "hard"), t.values)
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
        val t = field.type as FieldType.InlineEnum
        assertEquals(listOf("win", "loss"), t.values)
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
        val t = field.type as FieldType.InlineEnum
        assertEquals(listOf("a", "b", "c"), t.values)
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

class EnumTest {

    @Test
    fun testStandaloneEnumUnquoted() {
        val ddl = """
            database test {
              schema s {
                enum status(pending, active, completed)
                table task {
                  *id serial
                  status status
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val schema = db.schemas["s"]!!
        // Enum should be parsed
        val enum = schema.enums["status"]!!
        assertEquals(listOf("pending", "active", "completed"), enum.values)
        // Field should reference the enum by identity
        val field = schema.tables["task"]!!.fields["status"]!!
        val t = field.type as FieldType.NamedEnum
        assertSame(enum, t.enum)
    }

    @Test
    fun testStandaloneEnumQuoted() {
        val ddl = """
            database test {
              schema s {
                enum priority('low', 'medium', 'high')
                table task {
                  *id serial
                  priority priority
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val enum = db.schemas["s"]!!.enums["priority"]!!
        assertEquals(listOf("low", "medium", "high"), enum.values)
    }

    @Test
    fun testInlineEnumUnquoted() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  status enum(pending, active, completed)
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["status"]!!
        val t = field.type as FieldType.InlineEnum
        assertEquals(listOf("pending", "active", "completed"), t.values)
    }

    @Test
    fun testInlineEnumQuoted() {
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  status enum('a', 'b', 'c')
                }
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val field = db.schemas["s"]!!.tables["t"]!!.fields["status"]!!
        val t = field.type as FieldType.InlineEnum
        assertEquals(listOf("a", "b", "c"), t.values)
    }
}

class NamedEnumSQLTest {

    @Test
    fun testNamedEnumProducesNamedSqlType() {
        // A named enum referenced by a field must produce a SQL TYPE
        // whose name comes from the enum, not the field.
        val ddl = """
            database test {
              schema s {
                enum status(pending, active, completed)
                table task {
                  *id serial
                  task_status status
                }
              }
            }
        """.trimIndent()
        val sql = com.republicate.kddl.postgresql.PostgreSQLFormatter(false, false, false)
            .format(parse(CharStreams.fromString(ddl)))
        assertTrue(sql.contains("CREATE TYPE enum_status AS ENUM"),
            "Expected SQL type derived from enum name, got:\n$sql")
        assertFalse(sql.contains("enum_task_status"),
            "SQL type must not be derived from field name when a named enum is used:\n$sql")
    }

    @Test
    fun testSharedNamedEnumDedupedToSingleType() {
        // Two fields referencing the same named enum must share one SQL TYPE.
        val ddl = """
            database test {
              schema s {
                enum status(pending, active)
                table a {
                  *id serial
                  s status
                }
                table b {
                  *id serial
                  s status
                }
              }
            }
        """.trimIndent()
        val sql = com.republicate.kddl.postgresql.PostgreSQLFormatter(false, false, false)
            .format(parse(CharStreams.fromString(ddl)))
        val occurrences = Regex("CREATE TYPE enum_status AS ENUM").findAll(sql).count()
        assertEquals(1, occurrences, "Expected single CREATE TYPE for shared named enum, got $occurrences in:\n$sql")
    }

    @Test
    fun testInlineEnumStillPerField() {
        // Inline anonymous enums keep per-field naming (current policy).
        val ddl = """
            database test {
              schema s {
                table t {
                  *id serial
                  status enum('a', 'b')
                  mode enum('x', 'y')
                }
              }
            }
        """.trimIndent()
        val sql = com.republicate.kddl.postgresql.PostgreSQLFormatter(false, false, false)
            .format(parse(CharStreams.fromString(ddl)))
        assertTrue(sql.contains("CREATE TYPE enum_status AS ENUM"))
        assertTrue(sql.contains("CREATE TYPE enum_mode AS ENUM"))
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

    @Test
    fun testLinkChain() {
        // A *--* B --* C: many-to-many between A and B, one-to-many from B to C
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
                }
                table chapter {
                  *chapter_id serial
                  title varchar(100)
                }
                author *--* book --* chapter
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val schema = db.schemas["s"]!!

        // Join table for author *--* book
        assertTrue(schema.tables.containsKey("author_book"))
        val joinTable = schema.tables["author_book"]!!
        assertEquals(2, joinTable.foreignKeys.size)

        // FK on chapter for book --* chapter
        val chapter = schema.tables["chapter"]!!
        assertTrue(chapter.foreignKeys.isNotEmpty())
        assertEquals("book", chapter.foreignKeys.first().towards.name)
    }

    @Test
    fun testLinkChainWithOptional() {
        // A? *--* B? --* C: nullable FKs
        val ddl = """
            database test {
              schema s {
                table category {
                  *category_id serial
                  name varchar(100)
                }
                table product {
                  *product_id serial
                  name varchar(100)
                }
                table review {
                  *review_id serial
                  content text
                }
                category? *--* product? --* review
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val schema = db.schemas["s"]!!

        // Join table created
        assertTrue(schema.tables.containsKey("category_product"))

        // FK on review should be nullable (product? makes it optional)
        val review = schema.tables["review"]!!
        assertTrue(review.foreignKeys.isNotEmpty())
        val fk = review.foreignKeys.first()
        assertEquals("product", fk.towards.name)
        assertFalse(fk.nonNull)  // Should be nullable because product?
    }

    @Test
    fun testLongLinkChain() {
        // A --* B --* C --* D: cascading one-to-many
        val ddl = """
            database test {
              schema s {
                table company {
                  *company_id serial
                  name varchar(100)
                }
                table department {
                  *department_id serial
                  name varchar(100)
                }
                table team {
                  *team_id serial
                  name varchar(50)
                }
                table employee {
                  *employee_id serial
                  name varchar(100)
                }
                company --* department --* team --* employee
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val schema = db.schemas["s"]!!

        // department has FK to company
        val department = schema.tables["department"]!!
        assertEquals("company", department.foreignKeys.first().towards.name)

        // team has FK to department
        val team = schema.tables["team"]!!
        assertEquals("department", team.foreignKeys.first().towards.name)

        // employee has FK to team
        val employee = schema.tables["employee"]!!
        assertEquals("team", employee.foreignKeys.first().towards.name)
    }

    @Test
    fun testKddlChainOutput() {
        // Test that KDDL output generates chains from relations
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
                }
                table chapter {
                  *chapter_id serial
                  title varchar(100)
                }
                author *--* book --* chapter
              }
            }
        """.trimIndent()
        val db = parse(CharStreams.fromString(ddl))
        val output = db.display().toString()

        // Should contain the chain, not individual links
        assertTrue(output.contains("author *--* book --* chapter"), "Expected chain in output: $output")
        // Should NOT contain join table
        assertFalse(output.contains("author_book"), "Join table should be hidden: $output")
        // Should NOT contain implicit FK field in chapter
        assertFalse(output.contains("book_id ->"), "Implicit FK should be suppressed: $output")
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
        assertEquals("numeric(10,2)", table.fields["price"]!!.type.toString())
        assertEquals("numeric(5)", table.fields["quantity"]!!.type.toString())
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
        assertEquals("varchar(10)", table.fields["short_name"]!!.type.toString())
        assertEquals("varchar(255)", table.fields["long_name"]!!.type.toString())
        assertEquals("varchar", table.fields["unlimited"]!!.type.toString())
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
        assertEquals("timestamp", table.fields["created"]!!.type.toString())
        assertEquals("timestamp(3)", table.fields["updated"]!!.type.toString())
        assertEquals("timestamptz(6)", table.fields["with_tz"]!!.type.toString())
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
        assertEquals("uuid", table.fields["id"]!!.type.toString())
        assertEquals("json", table.fields["data"]!!.type.toString())
        assertEquals("varbit(8)", table.fields["bits"]!!.type.toString())
        assertEquals("interval", table.fields["duration"]!!.type.toString())
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

class IncludeTest {

    @Test
    fun testIncludeSyntaxParsed() {
        // Test that include syntax is recognized by the parser
        // Note: actual file loading will fail, but we can test the grammar
        val ddl = """
            include 'shared.kddl'
            database test {
              schema s {
                table t {
                  *id serial
                }
              }
            }
        """.trimIndent()

        // Use low-level parsing to check grammar without file resolution
        val lexer = com.republicate.kddl.parser.kddlLexer(CharStreams.fromString(ddl))
        val tokenStream = org.antlr.v4.kotlinruntime.CommonTokenStream(lexer)
        val parser = com.republicate.kddl.parser.kddlParser(tokenStream)
        val root = parser.database()

        // Verify include statements are parsed
        assertEquals(1, root.include_stmt().size)
        assertEquals("'shared.kddl'", root.include_stmt()[0].path!!.text)
        assertEquals("test", root.name!!.text)
    }

    @Test
    fun testMultipleIncludesSyntax() {
        val ddl = """
            include 'types.kddl'
            include 'base.kddl'
            database test {
              schema s {
                table t { *id serial }
              }
            }
        """.trimIndent()

        val lexer = com.republicate.kddl.parser.kddlLexer(CharStreams.fromString(ddl))
        val tokenStream = org.antlr.v4.kotlinruntime.CommonTokenStream(lexer)
        val parser = com.republicate.kddl.parser.kddlParser(tokenStream)
        val root = parser.database()

        assertEquals(2, root.include_stmt().size)
        assertEquals("'types.kddl'", root.include_stmt()[0].path!!.text)
        assertEquals("'base.kddl'", root.include_stmt()[1].path!!.text)
    }

    @Test
    fun testASTIncludeDisplay() {
        // Test ASTInclude display method
        val include = com.republicate.kddl.ASTInclude("shared/types.kddl")
        val output = include.display().toString()
        assertEquals("include 'shared/types.kddl'\n", output)
    }
}
