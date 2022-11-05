package com.republicate.kddl.postgresql

import com.republicate.kddl.*
import com.republicate.kddl.Formatter.Companion.EOL

class PostgreSQLFormatter(quoted: Boolean, uppercase: Boolean): SQLFormatter(quoted, uppercase) {

    override val supportsEnums = true
    override val supportsInheritance = true
    override val scopedObjectNames = true

    override fun defineEnum(field: ASTField) =
        "CREATE TYPE ${transform("enum_${field.name}")} AS ENUM ${field.type.substring(4)};${EOL}" +
        "CREATE CAST (varchar AS ${transform("enum_${field.name}")}) WITH INOUT AS IMPLICIT;"

    override fun defineInheritedView(table: ASTTable): String {
        val ret = StringBuilder()
        val parent = table.parent!!
        val viewName = transform(table.name)
        val baseName = viewName.removeSurrounding(Q)
        val tableName = "base_${baseName}"

        // View
        val parentName = transform(table.parent.name)
        val qualifiedParentName =
            if (table.schema == table.parent.schema) parentName
            else "${transform(parent.schema.name)}.$parentName"
        ret.append("CREATE VIEW $viewName AS${EOL}  SELECT${EOL}    ")
        val parentPkFields = parent.getPrimaryKey().map {
            "$parentName.${transform(it.name)}"
        }.joinToString(",")
        ret.append(parentPkFields)
        val parentNonPKFields = parent.fields.values
            .filter { !it.primaryKey }
            .map { transform(it.name) }
            .joinToString(",")
        if (parentNonPKFields.isNotEmpty()) {
            ret.append(",")
            ret.append(parentNonPKFields)
        }
        ret.append(",${Q}class$Q")
        val childFields = table.fields.values
            .map { transform(it.name) }
            .joinToString(",")
        if (childFields.isNotEmpty()) {
            ret.append(",${EOL}")
            ret.append("    $childFields")
        }
        ret.append(EOL)
        ret.append("  FROM ${transform(tableName)} JOIN $qualifiedParentName ON ")
        val join = parent.getPrimaryKey().map {
            "$parentName.${it.name} = $tableName.${it.name}"
        }.joinToString(" AND ")
        ret.append(join)
        ret.append("$END${EOL}")
        
        
        // Rules (only for single field primary key for now)
        if (parent.getPrimaryKey().size == 1) {

            val pk = parent.getPrimaryKey().elementAt(0)
            val pkName = transform(pk.name)

            if (pk.type == "serial") {

                var seqName = "${parent.name}_${pkName.removeSurrounding(Q)}_seq"
                if (table.schema != parent.schema) seqName = "${transform(parent.schema.name)}.$seqName"

                ret.append("CREATE RULE insert_${baseName} AS ON INSERT TO $viewName DO INSTEAD (${EOL}")

                ret.append("  INSERT INTO $qualifiedParentName ($pkName, $parentNonPKFields,${Q}class$Q)${EOL}    VALUES (")
                ret.append("     COALESCE(NEW.$pkName,NEXTVAL('$seqName')),")
                var parentValues = parent.fields.values.filter { !it.primaryKey }.map { "NEW.${transform(it.name)}" }.joinToString(",")
                ret.append("$parentValues,'$baseName')${EOL}")
                ret.append("  RETURNING $qualifiedParentName.*")
                if (childFields.isNotEmpty()) {
                    table.fields.values.forEach {
                        var nullType = when  {
                            // CB TODO - redundant with types map below
                            it.type.startsWith("varchar") -> "null::varchar"
                            it.type.startsWith("enum") -> "null::enum_${transform(it.name).removeSurrounding(Q)}"
                            it.type == "float" -> "null::real"
                            it.type == "double" -> "null::float"
                            it.type == "int" -> "null::integer"
                            else -> "null::${it.type}"
                        }
                        ret.append(",$nullType")
                    }
                }
                ret.append("$END${EOL}")

                ret.append("  SELECT SETVAL('$seqName', (SELECT MAX($pkName) FROM $qualifiedParentName)) $pkName$END")

                ret.append("  INSERT INTO $tableName ($pkName")
                if (childFields.isNotEmpty()) {
                    ret.append(",$childFields")
                }
                ret.append(")${EOL}    VALUES (")
                ret.append("CURRVAL('$seqName')")
                var childValues = table.fields.values.map { "NEW.${transform(it.name)}" }.joinToString(",")
                if (childValues.isNotEmpty()) {
                    ret.append(",$childValues")
                }
                ret.append(")$END")

                ret.append(")$END${EOL}")

            } else {

                ret.append("CREATE RULE insert_${baseName} AS ON INSERT TO $viewName DO INSTEAD (${EOL}")
                ret.append("  INSERT INTO $qualifiedParentName ($pkName,$parentNonPKFields,${Q}class$Q)${EOL}    VALUES (")
                val parentValues = parent.fields.values.map { "NEW.${transform(it.name)}" }.joinToString(",")
                ret.append("$parentValues,'$viewName')$END")
                ret.append("  INSERT INTO $tableName ($pkName")
                if (childFields.isNotEmpty()) {
                    ret.append(",$childFields")
                }
                ret.append(")${EOL}    VALUES (")
                var childValues = table.fields.values.map { "NEW.${transform(it.name)}" }.joinToString(",")
                ret.append("NEW.$pkName")
                if (childValues.isNotEmpty()) {
                    ret.append(",$childValues")
                }
                ret.append(")$END");
                ret.append(")$END${EOL}")
            }

            ret.append("CREATE RULE update_${baseName} AS ON UPDATE TO $viewName DO INSTEAD (${EOL}")
            ret.append("  UPDATE $qualifiedParentName${EOL}")
            ret.append("    SET ")
            val updateParent = parent.fields.values.filter { !it.primaryKey }
                .map { "${transform(it.name)} = NEW.${transform(it.name)}" }
                .joinToString(",")
            ret.append("$updateParent${EOL}    WHERE $pkName = NEW.$pkName${EOL}")
            ret.append("  RETURNING NEW.*$END")
            val updateChild = table.fields.values
                .map { "${transform(it.name)} = NEW.${transform(it.name)}" }
                .joinToString(",")
            if (updateChild.isNotEmpty()) {
                ret.append("  UPDATE $tableName${EOL}")
                ret.append("    SET ")
                ret.append("$updateChild${EOL}    WHERE $pkName = NEW.$pkName$END")
            }
            ret.append(")$END${EOL}")

            ret.append("CREATE RULE delete_${baseName} AS ON DELETE TO $viewName DO INSTEAD (${EOL}")
            // rely on cascade
            ret.append("  DELETE FROM $qualifiedParentName WHERE $pkName = OLD.$pkName$END")
            ret.append(")$END${EOL}")
        } else {
            throw Error("inheritance only supported for single field primary key")
        }
        return ret.toString()
    }

    private val typeMap = mapOf(
        "datetime" to "timestamp",
        "int" to "integer",
        "long" to "bigint",
        "float" to "real",
        "double" to "double precision",
        "blob" to "bytea"
    )

    override fun mapType(type: String) = typeMap[type]

    override fun setSchema(schema: String) = "SET search_path TO $schema$END"
}
