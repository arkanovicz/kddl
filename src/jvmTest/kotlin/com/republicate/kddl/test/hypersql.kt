package com.republicate.kddl.test

import com.republicate.kddl.hypersql.HyperSQLFormatter
import com.republicate.kddl.parse
import org.antlr.v4.kotlinruntime.CharStreams
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue

/** The HyperSQL dialect serves both HSQLDB and H2: its DDL has to run on each. */
class HyperSQLTest {

    private val ddl = """
        database d {
          schema s {
            table events {
              *events_id serial
              starts timestamptz
              precise timestamptz(6)?
              legacy datetime_tz?
              daily timetz?
            }
          }
        }
    """.trimIndent()

    private val sql = HyperSQLFormatter(quoted = false, uppercase = false).format(parse(CharStreams.fromString(ddl)))

    private fun run(url: String) {
        DriverManager.getConnection(url, "sa", "").use { cnx ->
            cnx.createStatement().use { st ->
                sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                    .split(';').map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { st.execute(it) }
            }
        }
    }

    @Test
    fun testZoneTypes() {
        assertTrue(sql.contains("starts timestamp with time zone NOT NULL"), sql)
        assertTrue(sql.contains("precise timestamp(6) with time zone"), sql)
        assertTrue(sql.contains("legacy timestamp with time zone"), sql)
        assertTrue(sql.contains("daily time with time zone"), sql)
    }

    @Test
    fun testRunsOnHsqldb() = run("jdbc:hsqldb:mem:kddl")

    @Test
    fun testRunsOnH2() = run("jdbc:h2:mem:kddl")
}
