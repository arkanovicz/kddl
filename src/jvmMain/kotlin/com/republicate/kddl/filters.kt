package com.republicate.kddl

import com.republicate.kddl.mysql.MySQLReverseFilter
import com.republicate.kddl.postgresql.PostgreSQLReverseFilter
import java.sql.DatabaseMetaData

/** What reverse engineering keeps of a column once the vendor filter had its say. */
data class ReversedColumn(val type: FieldType, val default: String?, val nonNull: Boolean)

interface ReverseFilter {
    companion object {
        fun getReverseFilter(metadata: DatabaseMetaData) : ReverseFilter {
            val match = Regex("^jdbc:([^:]+):").find(metadata.url) ?: throw RuntimeException("could not extract tag from jdbc url")
            return when (match.groups[1]?.value) {
                "postgresql" -> PostgreSQLReverseFilter(metadata)
                "mysql", "mariadb" -> MySQLReverseFilter(metadata)
                else -> object: ReverseFilter {}
            }
        }
    }
    /** [typeName] is the vendor's own name for the column type, the JDBC `TYPE_NAME`. */
    fun filterColumn(table: ASTTable, name: String, typeName: String, primaryKey: Boolean, column: ReversedColumn): ReversedColumn = column
}
