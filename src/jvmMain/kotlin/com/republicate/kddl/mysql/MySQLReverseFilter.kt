package com.republicate.kddl.mysql

import com.republicate.kddl.ASTField
import com.republicate.kddl.ASTTable
import com.republicate.kddl.FieldType
import com.republicate.kddl.ReverseFilter
import com.republicate.kddl.ReversedColumn
import java.sql.DatabaseMetaData

class MySQLReverseFilter(val metadata: DatabaseMetaData): ReverseFilter {

    // JDBC sees an enum column as a char: its labels only live in information_schema
    private val enums: Map<Triple<String, String, String>, List<String>> by lazy {
        buildMap {
            metadata.connection.createStatement().use { st ->
                st.executeQuery(
                    "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS WHERE DATA_TYPE = 'enum'"
                ).use { rs ->
                    while (rs.next()) put(Triple(rs.getString(1), rs.getString(2), rs.getString(3)), enumLabels(rs.getString(4)))
                }
            }
        }
    }

    override fun filterColumn(table: ASTTable, name: String, typeName: String, primaryKey: Boolean, column: ReversedColumn): ReversedColumn {
        var result = column
        if (typeName.equals("ENUM", true)) {
            enums[Triple(table.schema.name, table.name, name)]?.let { result = result.copy(type = FieldType.InlineEnum(it)) }
        }
        val default = result.default
        if (default != null) {
            // an old dump's '' or 0 on a key: a key is given, never defaulted
            if (primaryKey && !ASTField.isFunctionCall(default)) result = result.copy(default = null)
            // MySQL's "no date" is a null anywhere else
            else if (isZeroDate(default)) result = result.copy(default = null, nonNull = false)
        }
        return result
    }

    companion object {
        private val zeroDate = Regex("""^0000-00-00( 00:00:00(\.0+)?)?$""")
        internal fun isZeroDate(default: String) = zeroDate.matches(default)
        // labels of enum('a','b'), a quote inside a label being doubled
        private val label = Regex("""'((?:[^']|'')*)'""")
        internal fun enumLabels(columnType: String): List<String> =
            label.findAll(columnType).map { it.groupValues[1].replace("''", "'") }.toList()
    }
}
