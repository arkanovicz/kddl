package com.republicate.kddl

import com.republicate.kddl.Formatter.Companion.EOL

abstract class SQLFormatter(val quoted: Boolean, val uppercase: Boolean): Formatter {

    open val supportsEnums = false
    open val supportsInheritance = false
    open val scopedObjectNames = false
    open fun defineEnum(field: ASTField) = ""
    open fun defineInheritedView(table: ASTTable) = ""
    open fun setSchema(schema: String) = "SET SCHEMA $schema$END"

    protected open val END = ";${EOL}"
    protected open val Q = if (quoted) "\"" else ""
    val upper = Regex("[A-Z]")
    open fun transform(str: String) =
        if (uppercase) "${Q}${camelToSnake(str).uppercase()}$Q"
        else "${Q}${camelToSnake(str)}$Q"

    private val typeMap = mapOf(
        "datetime" to "timestamp",
        "int" to "integer",
        "long" to "bigint",
        "float" to "real",
        "double" to "double precision"
    )

    open fun mapType(type: String) = typeMap[type]

    // CB TODO - make it configurable
    private fun camelToSnake(camel : String) : String {
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

    override fun format(asm: ASTDatabase, indent: String): String {
        val ret = StringBuilder("-- database ${asm.name}${EOL}")
        // TODO postgresql options
        ret.append(
            asm.schemas.map {
                format(it.value, indent)
            }.joinToString(separator = EOL)
        )
        return ret.toString()
    }

    override fun format(asm: ASTSchema, indent: String): String {
        val ret = StringBuilder()
        val schemaName = transform(asm.name)
        ret.append("${EOL}-- schema $schemaName${EOL}")
        ret.append("DROP SCHEMA IF EXISTS $schemaName CASCADE$END")
        ret.append("CREATE SCHEMA $schemaName")
        // incorrect
//        val owner = asm.db.options["owner"]?.value ?: ""
//        if (owner.isNotEmpty()) ret.append(" WITH OWNER ${owner.removeSurrounding("'")}")
        ret.append(END)
        ret.append(setSchema(schemaName))
        if (supportsEnums) {
            ret.append(
                asm.tables.values.flatMap {
                    it.fields.values
                }.filter {
                    it.type.startsWith("enum(")
                }.map {
                    defineEnum(it)
                }.joinToString(separator = EOL))
        }
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
        if (supportsInheritance) {
            asm.tables.values.filter { it.parent != null }
                .forEach { tbl ->

                    // foreign key from base to parent
                    var parent = tbl.parent
                    val fkFields =  parent!!.getPrimaryKey().map {
                            field -> ASTField(tbl, field.name, field.type)
                    }.toSet()
                    val fk = ASTForeignKey(tbl, fkFields, parent, true, true, true)
                    ret.append(format(fk, indent)).append(EOL)
                }
        }
        return ret.toString()
    }

    override fun format(asm: ASTTable, indent: String): String {
        val ret = StringBuilder()
        var tableName = transform(asm.name)
        var viewName : String? = null;

        if (asm.parent != null) {
            if (!supportsInheritance) throw Error("inheritance not supported")
            tableName = "base_$tableName"
        }

        ret.append("CREATE TABLE $tableName (")
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
            if (!supportsInheritance) throw Error("inheritance not supported")
            if (firstField) firstField = false else ret.append(",")
            ret.append(EOL)
            ret.append("  class varchar(30)")
        }

        if (asm.parent == null) {
            val pkFields = asm.getPrimaryKey().map { transform(it.name) }.joinToString(",")
            if (pkFields.isNotEmpty()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(EOL)
                ret.append("  PRIMARY KEY ($pkFields)")
            }
        } else {
            val pkFields = asm.parent.getPrimaryKey().map { transform(it.name) }.joinToString(",")
            if (pkFields.isNotEmpty()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(EOL)
                ret.append("  PRIMARY KEY ($pkFields)")
            }
        }

        ret.append("${EOL})$END${EOL}")

        if (asm.parent != null) {
            ret.append(defineInheritedView(asm))
        }

        return ret.toString()
    }

    override fun format(asm: ASTField, indent: String): String {
        val ret = StringBuilder(indent)
        asm.apply {
            ret.append(transform(name))
            if (type.isEmpty()) throw RuntimeException("Missing type for ${asm.table.schema.name}.${asm.table.name}.${asm.name}")
            else if (type.startsWith("enum(")) ret.append(" enum_${transform(name)}")
            else ret.append(" ${mapType(type) ?: type}")
            if (nonNull) ret.append(" NOT NULL")
            // CB TODO - review 'unique' upstream calculation. A field should not be systematically
            // be marked as unique because it is part of a multivalued primary key, for instance.
            if (unique && !primaryKey) ret.append(" UNIQUE")
            when (default) {
                null -> 0 // NOP
                is Boolean -> ret.append(" DEFAULT $default")
                is Number -> ret.append(" DEFAULT $default")
                // is String -> ret.append("DEFAULT ${defaultMap[default] ?: default}")
                is String -> {
                    if (default.contains('(') && default.contains(')')) ret.append(" DEFAULT $default")
                    else ret.append(" DEFAULT '$default'")
                }
                is Function<*> -> ret.append(" DEFAULT $default()")
                is Function0<*> -> ret.append(" DEFAULT ${(default as Function0<String>).invoke()}")
                else -> throw RuntimeException("Unhandled default value type: $default")
            }
        }
        return ret.toString()
    }

    override fun format(asm: ASTForeignKey, indent: String): String {
        val src = asm.from
        val ret = StringBuilder()
        val srcName =
            if (src.parent == null) transform(src.name)
            else "base_${transform(src.name).removeSurrounding(Q)}"
        val fkName =
            if (scopedObjectNames) transform(asm.fields.first().name.removeSuffix(suffix))
            else "$Q${transform(src.name).removeSurrounding(Q)}_${transform(asm.fields.first().name.removeSuffix(suffix)).removeSurrounding(Q)}_fk$Q"
        ret.append("ALTER TABLE $srcName")
        ret.append(" ADD CONSTRAINT $fkName")
        ret.append(" FOREIGN KEY (${transform(asm.fields.map{it.name}.joinToString(","))})")
        ret.append(" REFERENCES ")

        if (asm.towards.schema.name != src.schema.name) {
            ret.append("$Q${transform(asm.towards.schema.name)}$Q.")
        }
        val dstName =
            if (asm.towards.parent == null) transform(asm.towards.name)
            else "${Q}base_${transform(asm.towards.name).removeSurrounding(Q)}$Q"
        ret.append("$dstName (${transform(asm.towards.getOrCreatePrimaryKey().map{it.name}.joinToString(","))})")
        if (asm.cascade) {
            ret.append(" ON DELETE CASCADE")
        }
        ret.append("$END")
        return ret.toString()
    }
}
