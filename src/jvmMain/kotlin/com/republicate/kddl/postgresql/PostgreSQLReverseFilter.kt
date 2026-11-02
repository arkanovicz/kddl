package com.republicate.kddl.postgresql

import com.republicate.kddl.ASTTable
import com.republicate.kddl.FieldType
import com.republicate.kddl.ReverseFilter
import com.republicate.kddl.ReversedColumn
import java.sql.DatabaseMetaData

class PostgreSQLReverseFilter(val metadata: DatabaseMetaData): ReverseFilter {

    val enumMap: Map<String, List<String>> by lazy {
        val map = mutableMapOf<String, List<String>>()
        val rs = metadata.connection.prepareStatement(
            """
                SELECT pg_type.typname, pg_enum.enumlabel
                  FROM pg_type
                  JOIN pg_enum ON pg_enum.enumtypid = pg_type.oid
                  ORDER BY typname, enumsortorder;
            """.trimIndent()).executeQuery()
        while (rs.next()) {
            val name = rs.getString("typname")
            val label = rs.getString("enumlabel")
            val values = map.getOrPut(name, { mutableListOf<String>() })
            (values as MutableList<String>).add(label)
        }
        map
    }

    override fun filterColumn(table: ASTTable, name: String, typeName: String, primaryKey: Boolean, column: ReversedColumn): ReversedColumn {
        // pgjdbc reports zoned types under their zoneless JDBC type
        val zoned = if (typeName == "timestamptz" || typeName == "timetz") column.copy(type = FieldType.Primitive(typeName)) else column
        val type = zoned.type as? FieldType.Primitive ?: return zoned
        val default = zoned.default
        return when {
            default?.startsWith("nextval(") ?: false ->
                zoned.copy(type = FieldType.Primitive(if (type.name == "bigint") "bigserial" else "serial"), default = null)
            default?.contains("::") ?: false -> zoned.copy(default = default!!.substring(0, default.indexOf("::")))
            // an enum column comes as an unbounded varchar, named after its type; text comes the same way, unnamed
            // CB TODO - the convention that enum fields with the same name share the type enum_${name} is kept as a fallback
            type.name == "varchar(2147483647)" ->
                zoned.copy(type = (enumMap[typeName] ?: enumMap["enum_$name"])?.let { FieldType.InlineEnum(it) } ?: FieldType.Primitive("text"))
            else -> zoned
        }
    }
}
