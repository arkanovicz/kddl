package com.republicate.kddl.postgresql

import com.republicate.kddl.ReverseFilter
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

    override fun filterType(name: String, type: String, default: String?): Pair<String, String?> {
        return when {
            default?.startsWith("nextval(") ?: false -> Pair("serial", null)
            default?.contains("::") ?: false -> Pair(type, default!!.substring(0, default!!.indexOf("::")))
            // CB TODO - for now we use the convention that in the database,
            // all enum fields with the same name share the same type with name enum_${name}
            type == "varchar(2147483647)" -> Pair(enumMap["enum_$name"]?.joinToString("','", "enum ('", "')") ?: "text", default)
            else -> Pair(type, default)
        }
    }
}