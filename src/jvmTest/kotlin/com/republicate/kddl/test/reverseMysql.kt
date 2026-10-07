package com.republicate.kddl.test

import com.republicate.kddl.ASTField
import com.republicate.kddl.FieldType
import com.republicate.kddl.mysql.MySQLReverseFilter
import com.republicate.kddl.reverse
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.testcontainers.DockerClientFactory
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import kotlin.test.*

class MySQLReverseFilterTest {

    @Test
    fun testEnumLabels() {
        assertEquals(listOf("Y", "N", "it's"), MySQLReverseFilter.enumLabels("enum('Y','N','it''s')"))
    }

    @Test
    fun testZeroDate() {
        assertTrue(MySQLReverseFilter.isZeroDate("0000-00-00"))
        assertTrue(MySQLReverseFilter.isZeroDate("0000-00-00 00:00:00"))
        assertFalse(MySQLReverseFilter.isZeroDate("2000-01-01"))
        assertFalse(MySQLReverseFilter.isZeroDate("CURRENT_TIMESTAMP"))
    }
}

/** What an old MySQL dump carries: enums, zero dates, defaults on keys. Read through a MariaDB, as the legacy bases are. */
class MySQLReverseTest {

    companion object {
        private var container: MySQLContainer? = null

        @BeforeClass @JvmStatic
        fun start() {
            assumeTrue("no Docker", DockerClientFactory.instance().isDockerAvailable)
            container = MySQLContainer(DockerImageName.parse("mariadb:10.11").asCompatibleSubstituteFor("mysql")).apply { start() }
        }

        @AfterClass @JvmStatic
        fun stop() { container?.stop() }
    }

    @Test
    fun testLegacyColumns() {
        val db = container!!
        val url = "${db.jdbcUrl}?user=${db.username}&password=${db.password}&allowPublicKeyRetrieval=true&useSSL=false"
        DriverManager.getConnection(url).use {
            it.createStatement().use { st ->
                st.execute("SET SESSION sql_mode = ''")
                st.execute("""
                    CREATE TABLE legacy (
                      legacy_id int NOT NULL DEFAULT 0,
                      code char(3) NOT NULL DEFAULT '',
                      status enum('Y','N') NOT NULL DEFAULT 'Y',
                      since date NOT NULL DEFAULT '0000-00-00',
                      seen datetime NOT NULL DEFAULT '0000-00-00 00:00:00',
                      born date DEFAULT NULL,
                      PRIMARY KEY (legacy_id, code)
                    )
                """.trimIndent())
            }
        }
        val fields = reverse(url).schemas.values.firstNotNullOf { it.tables["legacy"] }.fields
        fun field(name: String) = assertNotNull(fields[name], "missing $name in ${fields.keys}")
        assertEquals(listOf("legacy_id", "code", "status", "since", "seen", "born"), fields.keys.toList())
        // a key is given, never defaulted
        assertTrue(field("legacy_id").primaryKey)
        assertNull(field("legacy_id").default)
        assertEquals(FieldType.Primitive("char(3)"), field("code").type)
        assertNull(field("code").default)
        // an enum with its labels, and its own default kept
        assertEquals(FieldType.InlineEnum(listOf("Y", "N")), field("status").type)
        assertEquals("Y", field("status").default)
        assertTrue(field("status").nonNull)
        // a zero date is no date: nullable, no default
        for (name in listOf("since", "seen")) {
            assertNull(field(name).default, name)
            assertFalse(field(name).nonNull, name)
        }
        assertFalse(field("born").nonNull)
    }
}
