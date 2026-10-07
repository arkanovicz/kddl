package com.republicate.kddl.test

import com.republicate.kddl.FieldType
import com.republicate.kddl.reverse
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.*

class ReverseTest {

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

    private fun reverseTable(ddl: String, table: String): Map<String, FieldType> {
        val pg = container!!
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use {
            it.createStatement().use { st -> st.execute(ddl) }
        }
        val sep = if ('?' in pg.jdbcUrl) '&' else '?'
        val db = reverse("${pg.jdbcUrl}${sep}user=${pg.username}&password=${pg.password}")
        val fields = db.schemas.values.firstNotNullOf { it.tables[table] }.fields
        return fields.mapValues { it.value.type }
    }

    @Test
    fun testTypes() {
        val types = reverseTable("""
            CREATE TABLE types (
              types_id serial PRIMARY KEY,
              code char(3),
              ratio real,
              big double precision,
              body text
            )
        """.trimIndent(), "types")
        assertEquals(FieldType.Primitive("char(3)"), types["code"])
        assertEquals(FieldType.Primitive("float"), types["ratio"])
        assertEquals(FieldType.Primitive("double"), types["big"])
        assertEquals(FieldType.Primitive("text"), types["body"])
    }
}
