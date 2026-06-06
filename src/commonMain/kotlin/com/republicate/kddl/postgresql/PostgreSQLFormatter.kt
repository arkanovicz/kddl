package com.republicate.kddl.postgresql

import com.republicate.kddl.*
import com.republicate.kddl.Formatter.Companion.EOL

class PostgreSQLFormatter(quoted: Boolean, uppercase: Boolean, idempotent: Boolean = false): SQLFormatter(quoted, uppercase, idempotent) {

    override val supportsEnums = true
    override val supportsInheritance = true
    override val supportsPartialIndex = true
    override val scopedObjectNames = true

    private fun defaultExpression(field: ASTField): String? {
        return when (val d = field.default) {
            null -> null
            is Boolean -> d.toString()
            is Number -> d.toString()
            is String -> {
                if (d.contains('(') && d.contains(')')) {
                    // GENERATED ALWAYS columns can't be inserted into; skip COALESCE
                    if (d.startsWith("concat")) null
                    else d
                } else "'$d'"
            }
            is Function0<*> -> @Suppress("UNCHECKED_CAST") (d as Function0<String>).invoke()
            is Function<*> -> "$d()"
            else -> null
        }
    }

    private fun newOrDefault(field: ASTField): String {
        val ref = "NEW.${transform(field.name)}"
        val def = defaultExpression(field) ?: return ref
        return "COALESCE($ref, $def)"
    }

    override fun defineEnum(typeName: String, values: List<String>): String {
        val qTypeName = transform(typeName)
        val enumValues = "(${values.joinToString(",") { "'$it'" }})"
        return if (idempotent) {
            // Use DO block to create type only if it doesn't exist
            "DO $$ BEGIN${EOL}" +
            "  CREATE TYPE $qTypeName AS ENUM $enumValues;${EOL}" +
            "EXCEPTION WHEN duplicate_object THEN NULL;${EOL}" +
            "END $$;${EOL}" +
            "CREATE CAST (varchar AS $qTypeName) WITH INOUT AS IMPLICIT;"
        } else {
            "CREATE TYPE $qTypeName AS ENUM $enumValues;${EOL}" +
            "CREATE CAST (varchar AS $qTypeName) WITH INOUT AS IMPLICIT;"
        }
    }

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
        val parentPkFields = parent.getPrimaryKey().joinToString(",") {
            "$parentName.${transform(it.name)}"
        }
        ret.append(parentPkFields)
        val parentNonPKFields = parent.fields.values
            .filter { !it.primaryKey }.joinToString(",") { transform(it.name) }
        if (parentNonPKFields.isNotEmpty()) {
            ret.append(",")
            ret.append(parentNonPKFields)
        }
        ret.append(",${Q}class$Q")
        val childFields = table.fields.values.joinToString(",") { transform(it.name) }
        if (childFields.isNotEmpty()) {
            ret.append(",${EOL}")
            ret.append("    $childFields")
        }
        ret.append(EOL)
        ret.append("  FROM ${transform(tableName)} JOIN $qualifiedParentName ON ")
        val join = parent.getPrimaryKey().joinToString(" AND ") {
            "$parentName.${it.name} = $tableName.${it.name}"
        }
        ret.append(join)
        ret.append("$END${EOL}")
        
        
        // Rules (only for single field primary key for now)
        if (parent.getPrimaryKey().size == 1) {

            val pk = parent.getPrimaryKey().elementAt(0)
            val pkName = transform(pk.name)

            val pkT = pk.type
            if (pkT is FieldType.Primitive && pkT.name == "serial") {

                var seqName = "${parent.name}_${pkName.removeSurrounding(Q)}_seq"
                if (table.schema != parent.schema) seqName = "${transform(parent.schema.name)}.$seqName"

                ret.append("CREATE RULE insert_${baseName} AS ON INSERT TO $viewName DO INSTEAD (${EOL}")

                ret.append("  INSERT INTO $qualifiedParentName ($pkName, $parentNonPKFields,${Q}class$Q)${EOL}    VALUES (")
                ret.append("     COALESCE(NEW.$pkName,NEXTVAL('$seqName')),")
                var parentValues = parent.fields.values.filter { !it.primaryKey }.map { newOrDefault(it) }.joinToString(",")
                ret.append("$parentValues,'$baseName')${EOL}")
                ret.append("  RETURNING $qualifiedParentName.*")
                if (childFields.isNotEmpty()) {
                    table.fields.values.forEach {
                        val t = it.type
                        var nullType = when {
                            t is FieldType.NamedEnum -> "null::enum_${transform(t.enum.name).removeSurrounding(Q)}"
                            t is FieldType.InlineEnum -> "null::enum_${transform(it.name).removeSurrounding(Q)}"
                            t is FieldType.Primitive && t.base == "varchar" -> "null::varchar"
                            t is FieldType.Primitive && t.name == "float" -> "null::real"
                            t is FieldType.Primitive && t.name == "double" -> "null::float"
                            t is FieldType.Primitive && t.name == "int" -> "null::integer"
                            t is FieldType.Primitive -> "null::${t.name}"
                            else -> "null::$t"
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
                var childValues = table.fields.values.joinToString(",") { newOrDefault(it) }
                if (childValues.isNotEmpty()) {
                    ret.append(",$childValues")
                }
                ret.append(")$END")

                ret.append(")$END${EOL}")

            } else {

                ret.append("CREATE RULE insert_${baseName} AS ON INSERT TO $viewName DO INSTEAD (${EOL}")
                ret.append("  INSERT INTO $qualifiedParentName ($pkName,$parentNonPKFields,${Q}class$Q)${EOL}    VALUES (")
                val parentValues = parent.fields.values.joinToString(",") { newOrDefault(it) }
                ret.append("$parentValues,'$viewName')$END")
                ret.append("  INSERT INTO $tableName ($pkName")
                if (childFields.isNotEmpty()) {
                    ret.append(",$childFields")
                }
                ret.append(")${EOL}    VALUES (")
                var childValues = table.fields.values.joinToString(",") { newOrDefault(it) }
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
                .joinToString(",") { "${transform(it.name)} = NEW.${transform(it.name)}" }
            ret.append("$updateParent${EOL}    WHERE $pkName = NEW.$pkName${EOL}")
            ret.append("  RETURNING NEW.*$END")
            val updateChild =
                table.fields.values.joinToString(",") { "${transform(it.name)} = NEW.${transform(it.name)}" }
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
        "int" to "integer",
        "long" to "bigint",
        "float" to "real",
        "double" to "double precision",
        "blob" to "bytea",
        "datetime" to "timestamp",
        "datetimetz" to "timestamptz",
        "datetime_tz" to "timestamptz",
        "timestamp_tz" to "timestamptz"
    )

    override fun mapType(type: String) = typeMap[type]

    override fun setSchema(schema: String) = "SET search_path TO $schema,public$END"
}
