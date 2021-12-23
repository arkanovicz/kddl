package com.republicate.kddl.postgresql

import com.republicate.kddl.*
import com.republicate.kddl.Formatter.Companion.EOL

class PostgreSQLFormatter: Formatter {

    val END = ";$EOL"
    val Q = "\""

    val upper = Regex("[A-Z]")
    // CB TODO - make it configurable
    fun camelToSnake(camel : String) : String {
        val ret = StringBuilder()
        var pos = 0
        upper.findAll(camel).forEach {
            val match = it.range.first
            ret.append(camel.substring(pos, match))
            if (match > pos && camel[match - 1] != '_') ret.append('_')
            ret.append(it.value.toLowerCase())
            pos = match + 1
        }
        ret.append(camel.substring(pos))
        return ret.toString()
    }

    override fun format(asm: Database, indent: String): String {
        val ret = StringBuilder("-- database ${asm.name}$EOL")
        // TODO postgresql options
        ret.append(
            asm.schemas.map {
                format(it.value, indent)
            }.joinToString(separator = EOL)
        )
        return ret.toString()
    }

    override fun format(asm: Schema, indent: String): String {
        val ret = StringBuilder()
        val schemaName = camelToSnake(asm.name)
        ret.append("$EOL-- schema $schemaName$EOL")
        ret.append("DROP SCHEMA IF EXISTS $schemaName CASCADE$END")
        ret.append("CREATE SCHEMA $schemaName")
        // incorrect
//        val owner = asm.db.options["owner"]?.value ?: ""
//        if (owner.isNotEmpty()) ret.append(" WITH OWNER ${owner.removeSurrounding("'")}")
        ret.append(END)
        ret.append("SET search_path TO $schemaName$END")
        ret.append(
            asm.tables.values.flatMap {
                it.fields.values
            }.filter {
                it.type.startsWith("enum(")
            }.map {
                "CREATE TYPE enum_${camelToSnake(it.name)} AS ENUM ${it.type.substring(4)};$EOL" +
                        "CREATE CAST (varchar AS enum_${camelToSnake(it.name)}) WITH INOUT AS IMPLICIT;"
            }.joinToString(separator = EOL)
        )
        ret.append(EOL)
        ret.append(
            asm.tables.values.map {
                format(it, indent)
            }.joinToString(separator = EOL)
        )
        asm.tables.values.flatMap{ it.foreignKeys }/*.filter {
            !it.isFieldLink()
        }*/.forEach {
            ret.append(format(it, indent))
        }

        // foriegn key from child to parent table
        asm.tables.values.filter { it.parent != null }
            .forEach { tbl ->

                // foreign key from base to parent
                var parent = tbl.parent
                val fkFields =  parent!!.getPrimaryKey().map {
                        field -> Field(tbl, field.name, field.type)
                }.toSet()
                val fk = ForeignKey(tbl, fkFields, parent, true, true)
                ret.append(format(fk, indent)).append(EOL)
            }

        return ret.toString()
    }

    override fun format(asm: Table, indent: String): String {
        val ret = StringBuilder()
        var tableName = camelToSnake(asm.name)
        var viewName : String? = null;

        if (asm.parent != null) {
            viewName = tableName
            tableName = "base_$tableName"
        }

        ret.append("CREATE TABLE ${asm.schema.name}.$Q$tableName$Q (")
        var firstField = true

        for (field in asm.fields.values.filter { it.primaryKey }) {
            if (firstField) firstField = false else ret.append(",")
            ret.append(EOL)
            ret.append(format(field, "  "))
        }

        // duplicate inherited primary key fields
        if (asm.parent != null) {
            for (field in asm.parent.getPrimaryKey()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(EOL)
                ret.append(format(field, "  "))
            }
        }

        for (field in asm.fields.values.filter { !it.primaryKey }) {
            if (firstField) firstField = false else ret.append(",")
            ret.append(EOL)
            ret.append(format(field, "  "))
        }

        // DB TODO double inheritance is not handled
        if (asm.children.isNotEmpty()) {
            if (firstField) firstField = false else ret.append(",")
            ret.append(EOL)
            ret.append("  class varchar(30)")
        }

        if (asm.parent == null) {
            val pkFields = asm.getPrimaryKey().map { camelToSnake(it.name) }.joinToString(",")
            if (pkFields.isNotEmpty()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(EOL)
                ret.append("  PRIMARY KEY ($pkFields)")
            }
        } else {
            val pkFields = asm.parent.getPrimaryKey().map { camelToSnake(it.name) }.joinToString(",")
            if (pkFields.isNotEmpty()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(EOL)
                ret.append("  PRIMARY KEY ($pkFields)")
            }
        }

        ret.append("$EOL)$END$EOL")

        if (asm.parent != null) {

            // View
            val parent = asm.parent
            val parentName = "$Q${camelToSnake(asm.parent.name)}$Q"
            val qualifiedParentName =
                if (asm.schema == asm.parent.schema) parentName
                else "$Q${parent.schema.name}$Q.$parentName"
            ret.append("CREATE VIEW $viewName AS$EOL  SELECT$EOL    ")
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
            val childFields = asm.fields.values
                .map { camelToSnake(it.name) }
                .joinToString(",")
            if (childFields.isNotEmpty()) {
                ret.append(",$EOL")
                ret.append("    $childFields")
            }
            ret.append(EOL)
            ret.append("  FROM $Q$tableName$Q JOIN $qualifiedParentName ON ")
            val join = parent.getPrimaryKey().map {
                "$parentName.${it.name} = $Q$tableName$Q.${it.name}"
            }.joinToString(" AND ")
            ret.append(join)
            ret.append("$END$EOL")

            // Rules (only for single field primary key for now)
            if (parent.getPrimaryKey().size == 1) {

                val pk = parent.getPrimaryKey().elementAt(0)
                val pkName = camelToSnake(pk.name)

                if (pk.type == "serial") {

                    var seqName = "${camelToSnake(parent.name)}_${pkName}_seq"
                    if (asm.schema != parent.schema) seqName = "$Q${parent.schema.name}$Q.$seqName"

                    ret.append("CREATE RULE ${Q}insert_${viewName}$Q AS ON INSERT TO $Q$viewName$Q DO INSTEAD ($EOL")

                    ret.append("  INSERT INTO $qualifiedParentName ($pkName, $parentNonPKFields,class)$EOL    VALUES (")
                    ret.append("     COALESCE(NEW.$pkName,NEXTVAL('$seqName')),")
                    var parentValues = parent.fields.values.filter { !it.primaryKey }.map { "NEW.${camelToSnake(it.name)}" }.joinToString(",")
                    ret.append("$parentValues,'$viewName')$EOL")
                    ret.append("  RETURNING $qualifiedParentName.*")
                    if (childFields.isNotEmpty()) {
                        asm.fields.values.forEach {
                            var nullType = when  {
                                it.type.startsWith("varchar") -> "null::varchar"
                                it.type.startsWith("enum") -> "null::enum_${camelToSnake(it.name)}"
                                it.type == "float" -> "null::real"
                                it.type == "double" -> "null::float"
                                else -> "null::${it.type}"
                            }
                            ret.append(",$nullType")
                        }
                    }
                    ret.append("$END$EOL")

                    ret.append("  SELECT SETVAL('$seqName', (SELECT MAX($pkName) FROM $qualifiedParentName)) $pkName$END")

                    ret.append("  INSERT INTO $Q$tableName$Q ($pkName")
                    if (childFields.isNotEmpty()) {
                        ret.append(",$childFields")
                    }
                    ret.append(")$EOL    VALUES (")
                    ret.append("CURRVAL('$seqName')")
                    var childValues = asm.fields.values.map { "NEW.${camelToSnake(it.name)}" }.joinToString(",")
                    if (childValues.isNotEmpty()) {
                        ret.append(",$childValues")
                    }
                    ret.append(")$END")

                    ret.append(")$END$EOL")

                } else {

                    ret.append("CREATE RULE ${Q}insert_${viewName}$Q AS ON INSERT TO $Q$viewName$Q DO INSTEAD ($EOL")
                    ret.append("  INSERT INTO $qualifiedParentName ($pkName,$parentNonPKFields,class)$EOL    VALUES (")
                    val parentValues = parent.fields.values.map { "NEW.${camelToSnake(it.name)}" }.joinToString(",")
                    ret.append("$parentValues,'$viewName')$END")
                    ret.append("  INSERT INTO $Q$tableName$Q ($pkName")
                    if (childFields.isNotEmpty()) {
                        ret.append(",$childFields")
                    }
                    ret.append(")$EOL    VALUES (")
                    var childValues = asm.fields.values.map { "NEW.${camelToSnake(it.name)}" }.joinToString(",")
                    ret.append("NEW.$pkName")
                    if (childValues.isNotEmpty()) {
                        ret.append(",$childValues")
                    }
                    ret.append(")$END");
                    ret.append(")$END$EOL")
                }

                ret.append("CREATE RULE ${Q}update_${viewName}$Q AS ON UPDATE TO $Q$viewName$Q DO INSTEAD ($EOL")
                ret.append("  UPDATE $qualifiedParentName$EOL")
                ret.append("    SET ")
                val updateParent = parent.fields.values.filter { !it.primaryKey }
                    .map { "${camelToSnake(it.name)} = NEW.${camelToSnake(it.name)}" }
                    .joinToString(",")
                ret.append("$updateParent$EOL    WHERE $pkName = NEW.$pkName$EOL")
                ret.append("  RETURNING NEW.*$END")
                val updateChild = asm.fields.values
                    .map { "${camelToSnake(it.name)} = NEW.${camelToSnake(it.name)}" }
                    .joinToString(",")
                if (updateChild.isNotEmpty()) {
                    ret.append("  UPDATE $Q$tableName$Q$EOL")
                    ret.append("    SET ")
                    ret.append("$updateChild$EOL    WHERE $pkName = NEW.$pkName$END")
                }
                ret.append(")$END$EOL")

                ret.append("CREATE RULE ${Q}delete_${viewName}$Q AS ON DELETE TO $Q$viewName$Q DO INSTEAD ($EOL")
                // rely on cascade
                ret.append("  DELETE FROM $qualifiedParentName WHERE $pkName = OLD.$pkName$END")
                ret.append(")$END$EOL")
            }
        }

        return ret.toString()
    }

    private val typeMap = mapOf(
        "datetime" to "timestamp",
        "long" to "bigint",
        "float" to "real",
        "double" to "double precision"
    )

    override fun format(asm: Field, indent: String): String {
        val ret = StringBuilder(indent)
        asm.apply {
            ret.append(camelToSnake(name))
            if (type.isEmpty()) throw RuntimeException("Missing type for ${asm.table.schema.name}.${asm.table.name}.${asm.name}")
            else if (type.startsWith("enum(")) ret.append(" enum_${camelToSnake(name)}")
            else ret.append(" ${typeMap[type] ?: type}")
            if (nonNull) ret.append(" NOT NULL")
            if (unique) ret.append(" UNIQUE")
            when (default) {
                null -> 0 // NOP
                is Boolean -> ret.append(" DEFAULT $default")
                is Number -> ret.append(" DEFAULT $default")
                // is String -> ret.append("DEFAULT ${defaultMap[default] ?: default}")
                is String -> ret.append(" DEFAULT '$default'")
                is Function<*> -> ret.append(" DEFAULT $default()")
                is Function0<*> -> ret.append(" DEFAULT ${(default as Function0<String>).invoke()}")
                else -> throw RuntimeException("Unhandled default value type: $default")
            }
        }
        return ret.toString()
    }

    override fun format(asm: ForeignKey, indent: String): String {
        val src = asm.from
        val ret = StringBuilder()
        val srcName =
            if (src.parent == null) "$Q${camelToSnake(src.name)}$Q"
            else "${Q}base_${camelToSnake(src.name)}$Q"
        ret.append("ALTER TABLE $srcName")
        ret.append(" ADD CONSTRAINT $Q${camelToSnake(asm.fields.first().name.removeSuffix(suffix))}$Q")
        ret.append(" FOREIGN KEY (${camelToSnake(asm.fields.map{it.name}.joinToString(","))})")
        ret.append(" REFERENCES ")

        if (asm.towards.schema.name != src.schema.name) {
            ret.append("$Q${camelToSnake(asm.towards.schema.name)}$Q.")
        }
        val dstName =
            if (asm.towards.parent == null) "$Q${camelToSnake(asm.towards.name)}$Q"
            else "${Q}base_${camelToSnake(asm.towards.name)}$Q"
        ret.append("$dstName (${camelToSnake(asm.towards.getOrCreatePrimaryKey().map{it.name}.joinToString(","))})")
        if (asm.cascade) {
            ret.append(" ON DELETE CASCADE")
        }
        ret.append("$END")
        return ret.toString()
    }
}
