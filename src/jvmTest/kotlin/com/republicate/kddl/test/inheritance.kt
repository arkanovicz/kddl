package com.republicate.kddl.test

import com.republicate.kddl.parse
import com.republicate.kddl.postgresql.PostgreSQLFormatter
import org.antlr.v4.kotlinruntime.CharStreams
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.*

/**
 * An insert through a child view goes through the rule PostgreSQL rewrites it into, and only that
 * rule's last action can hand the row back: without it, JDBC's generated keys come back empty.
 */
class InheritedInsertTest {

    companion object {
        private var container: PostgreSQLContainer? = null

        @BeforeClass @JvmStatic
        fun start() {
            assumeTrue("no Docker", DockerClientFactory.instance().isDockerAvailable)
            container = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        }

        @AfterClass @JvmStatic
        fun stop() { container?.stop() }
    }

    private fun connect(model: String): Connection {
        val pg = container!!
        val ddl = PostgreSQLFormatter(quoted = false, uppercase = false).format(parse(CharStreams.fromString(model)))
        return DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).also {
            it.createStatement().use { st -> st.execute(ddl) }
        }
    }

    private val serialModel = """
        database d {
          schema serial_key {
            table person {
              *person_id serial
              name varchar(50)
            }
            table vip : person { since date }
          }
        }
    """.trimIndent()

    @Test
    fun testGeneratedKeys() {
        connect(serialModel).use { db ->
            db.createStatement().use { st ->
                assertEquals(1, st.executeUpdate(
                    "INSERT INTO vip (name, since) VALUES ('Ada', '2026-01-01')",
                    Statement.RETURN_GENERATED_KEYS))
                st.generatedKeys.use { keys ->
                    assertTrue(keys.next(), "no generated key")
                    assertEquals(1, keys.getInt("person_id"))
                }
            }
        }
    }

    @Test
    fun testReturning() {
        connect(serialModel.replace("serial_key", "serial_returning")).use { db ->
            db.createStatement().use { st ->
                st.executeQuery("INSERT INTO vip (name, since) VALUES ('Ada', '2026-01-01') RETURNING person_id, name, kind").use { rs ->
                    assertTrue(rs.next(), "no row returned")
                    assertEquals(1, rs.getInt(1))
                    assertEquals("Ada", rs.getString(2))
                    assertEquals("vip", rs.getString(3))
                }
            }
        }
    }

    @Test
    fun testCrossSchemaChild() {
        val model = """
            database d {
              schema cross_root {
                table person {
                  *person_id serial
                  name varchar(50)
                }
              }
              schema cross_child {
                table vip : cross_root.person { since date }
              }
            }
        """.trimIndent()
        connect(model).use { db ->
            db.createStatement().use { st ->
                st.executeQuery("INSERT INTO cross_child.vip (name, since) VALUES ('Ada', '2026-01-01') RETURNING person_id, kind").use { rs ->
                    assertTrue(rs.next(), "no row returned")
                    assertEquals(1, rs.getInt(1))
                    assertEquals("vip", rs.getString(2))
                }
            }
        }
    }

    @Test
    fun testReturningExample() {
        connect(Thread.currentThread().contextClassLoader.getResource("example.kddl")!!.readText()).use { db ->
            db.createStatement().use { st ->
                st.execute("SET search_path TO infra")
                st.executeQuery("INSERT INTO department (code, type, name) VALUES ('d1', 'urban', 'Dept') RETURNING code, kind").use { rs ->
                    assertTrue(rs.next(), "no row returned")
                    assertEquals("d1", rs.getString(1))
                    assertEquals("department", rs.getString(2))
                }
            }
        }
    }
}
