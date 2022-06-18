package com.republicate.kddl.postgresql

import com.republicate.kddl.*
import com.republicate.kddl.Formatter.Companion.EOL

class PostgreSQLFormatter: SQLFormatter() {

    override val supportsEnums = true
    override val supportsInheritance = true

    override fun defineEnum(field: ASTField) =
        "CREATE TYPE enum_${camelToSnake(field.name)} AS ENUM ${field.type.substring(4)};${Formatter.EOL}" +
        "CREATE CAST (varchar AS enum_${camelToSnake(field.name)}) WITH INOUT AS IMPLICIT;"

    override fun defineInheritedView(table: ASTTable): String {
        val ret = StringBuilder()
        val parent = table.parent!!
        val viewName = camelToSnake(table.name)
        val tableName = "base_${viewName}"

        // View
        val parentName = "$Q${camelToSnake(table.parent.name)}$Q"
        val qualifiedParentName =
            if (table.schema == table.parent.schema) parentName
            else "$Q${parent.schema.name}$Q.$parentName"
        ret.append("CREATE VIEW $viewName AS${Formatter.EOL}  SELECT${Formatter.EOL}    ")
        val parentPkFields = parent.getPrimaryKey().map {
            "$parentName.${camelToSnake(it.name)}"
        }.joinToString(",")
        ret.append(parentPkFields)
        val parentNonPKFields = parent.fields.values
            .filter { !it.primaryKey }
            .map { camelToSnake(it.name) }
            .joinToString(",")
        if (parentNonPKFields.isNotEmpty()) {
            ret.append(",")
            ret.append(parentNonPKFields)
        }
        ret.append(",class")
        val childFields = table.fields.values
            .map { camelToSnake(it.name) }
            .joinToString(",")
        if (childFields.isNotEmpty()) {
            ret.append(",${Formatter.EOL}")
            ret.append("    $childFields")
        }
        ret.append(Formatter.EOL)
        ret.append("  FROM $Q$tableName$Q JOIN $qualifiedParentName ON ")
        val join = parent.getPrimaryKey().map {
            "$parentName.${it.name} = $Q$tableName$Q.${it.name}"
        }.joinToString(" AND ")
        ret.append(join)
        ret.append("$END${Formatter.EOL}")
        
        
        // Rules (only for single field primary key for now)
        if (parent.getPrimaryKey().size == 1) {

            val pk = parent.getPrimaryKey().elementAt(0)
            val pkName = camelToSnake(pk.name)

            if (pk.type == "serial") {

                var seqName = "${camelToSnake(parent.name)}_${pkName}_seq"
                if (table.schema != parent.schema) seqName = "$Q${parent.schema.name}$Q.$seqName"

                ret.append("CREATE RULE ${Q}insert_${viewName}$Q AS ON INSERT TO $Q$viewName$Q DO INSTEAD (${Formatter.EOL}")

                ret.append("  INSERT INTO $qualifiedParentName ($pkName, $parentNonPKFields,class)${Formatter.EOL}    VALUES (")
                ret.append("     COALESCE(NEW.$pkName,NEXTVAL('$seqName')),")
                var parentValues = parent.fields.values.filter { !it.primaryKey }.map { "NEW.${camelToSnake(it.name)}" }.joinToString(",")
                ret.append("$parentValues,'$viewName')${Formatter.EOL}")
                ret.append("  RETURNING $qualifiedParentName.*")
                if (childFields.isNotEmpty()) {
                    table.fields.values.forEach {
                        var nullType = when  {
                            // CB TODO - redundant with types map below
                            it.type.startsWith("varchar") -> "null::varchar"
                            it.type.startsWith("enum") -> "null::enum_${camelToSnake(it.name)}"
                            it.type == "float" -> "null::real"
                            it.type == "double" -> "null::float"
                            it.type == "int" -> "null::integer"
                            else -> "null::${it.type}"
                        }
                        ret.append(",$nullType")
                    }
                }
                ret.append("$END${Formatter.EOL}")

                ret.append("  SELECT SETVAL('$seqName', (SELECT MAX($pkName) FROM $qualifiedParentName)) $pkName$END")

                ret.append("  INSERT INTO $Q$tableName$Q ($pkName")
                if (childFields.isNotEmpty()) {
                    ret.append(",$childFields")
                }
                ret.append(")${Formatter.EOL}    VALUES (")
                ret.append("CURRVAL('$seqName')")
                var childValues = table.fields.values.map { "NEW.${camelToSnake(it.name)}" }.joinToString(",")
                if (childValues.isNotEmpty()) {
                    ret.append(",$childValues")
                }
                ret.append(")$END")

                ret.append(")$END${Formatter.EOL}")

            } else {

                ret.append("CREATE RULE ${Q}insert_${viewName}$Q AS ON INSERT TO $Q$viewName$Q DO INSTEAD (${Formatter.EOL}")
                ret.append("  INSERT INTO $qualifiedParentName ($pkName,$parentNonPKFields,class)${Formatter.EOL}    VALUES (")
                val parentValues = parent.fields.values.map { "NEW.${camelToSnake(it.name)}" }.joinToString(",")
                ret.append("$parentValues,'$viewName')$END")
                ret.append("  INSERT INTO $Q$tableName$Q ($pkName")
                if (childFields.isNotEmpty()) {
                    ret.append(",$childFields")
                }
                ret.append(")${Formatter.EOL}    VALUES (")
                var childValues = table.fields.values.map { "NEW.${camelToSnake(it.name)}" }.joinToString(",")
                ret.append("NEW.$pkName")
                if (childValues.isNotEmpty()) {
                    ret.append(",$childValues")
                }
                ret.append(")$END");
                ret.append(")$END${Formatter.EOL}")
            }

            ret.append("CREATE RULE ${Q}update_${viewName}$Q AS ON UPDATE TO $Q$viewName$Q DO INSTEAD (${Formatter.EOL}")
            ret.append("  UPDATE $qualifiedParentName${Formatter.EOL}")
            ret.append("    SET ")
            val updateParent = parent.fields.values.filter { !it.primaryKey }
                .map { "${camelToSnake(it.name)} = NEW.${camelToSnake(it.name)}" }
                .joinToString(",")
            ret.append("$updateParent${Formatter.EOL}    WHERE $pkName = NEW.$pkName${Formatter.EOL}")
            ret.append("  RETURNING NEW.*$END")
            val updateChild = table.fields.values
                .map { "${camelToSnake(it.name)} = NEW.${camelToSnake(it.name)}" }
                .joinToString(",")
            if (updateChild.isNotEmpty()) {
                ret.append("  UPDATE $Q$tableName$Q${Formatter.EOL}")
                ret.append("    SET ")
                ret.append("$updateChild${Formatter.EOL}    WHERE $pkName = NEW.$pkName$END")
            }
            ret.append(")$END${Formatter.EOL}")

            ret.append("CREATE RULE ${Q}delete_${viewName}$Q AS ON DELETE TO $Q$viewName$Q DO INSTEAD (${Formatter.EOL}")
            // rely on cascade
            ret.append("  DELETE FROM $qualifiedParentName WHERE $pkName = OLD.$pkName$END")
            ret.append(")$END${Formatter.EOL}")
        } else {
            throw Error("inheritance only supported for single field primary key")
        }
        return ret.toString()
    }

    override fun setSchema(schema: String) = "SET search_path TO $schema$END"
}
