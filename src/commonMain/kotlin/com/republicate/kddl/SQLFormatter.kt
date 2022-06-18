package com.republicate.kddl

abstract class SQLFormatter: Formatter {

    open val supportsEnums = false
    open val supportsInheritance = false
    open fun defineEnum(field: ASTField) = ""
    open fun defineInheritedView(table: ASTTable) = ""
    open fun setSchema(schema: String) = "SET SCHEMA $schema$END"

    val END = ";${Formatter.EOL}"
    val Q = "\""
    val upper = Regex("[A-Z]")

    private val typeMap = mapOf(
        "datetime" to "timestamp",
        "int" to "integer",
        "long" to "bigint",
        "float" to "real",
        "double" to "double precision"
    )

    open fun mapType(type: String) = typeMap[type]

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

    override fun format(asm: ASTDatabase, indent: String): String {
        val ret = StringBuilder("-- database ${asm.name}${Formatter.EOL}")
        // TODO postgresql options
        ret.append(
            asm.schemas.map {
                format(it.value, indent)
            }.joinToString(separator = Formatter.EOL)
        )
        return ret.toString()
    }

    override fun format(asm: ASTSchema, indent: String): String {
        val ret = StringBuilder()
        val schemaName = camelToSnake(asm.name)
        ret.append("${Formatter.EOL}-- schema $schemaName${Formatter.EOL}")
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
                }.joinToString(separator = Formatter.EOL))
        }
        ret.append(Formatter.EOL)
        ret.append(
            asm.tables.values.map {
                format(it, indent)
            }.joinToString(separator = Formatter.EOL)
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
                    ret.append(format(fk, indent)).append(Formatter.EOL)
                }
        }
        return ret.toString()
    }

    override fun format(asm: ASTTable, indent: String): String {
        val ret = StringBuilder()
        var tableName = camelToSnake(asm.name)
        var viewName : String? = null;

        if (asm.parent != null) {
            if (!supportsInheritance) throw Error("inheritance not supported")
            tableName = "base_$tableName"
        }

        ret.append("CREATE TABLE $Q$tableName$Q (")
        var firstField = true

        for (field in asm.fields.values.filter { it.primaryKey }) {
            if (firstField) firstField = false else ret.append(",")
            ret.append(Formatter.EOL)
            ret.append(format(field, "  "))
        }

        // duplicate inherited primary key fields
        if (asm.parent != null) {
            for (field in asm.parent.getPrimaryKey()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(Formatter.EOL)
                ret.append(format(field, "  "))
            }
        }

        for (field in asm.fields.values.filter { !it.primaryKey }) {
            if (firstField) firstField = false else ret.append(",")
            ret.append(Formatter.EOL)
            ret.append(format(field, "  "))
        }

        // DB TODO double inheritance is not handled
        if (asm.children.isNotEmpty()) {
            if (!supportsInheritance) throw Error("inheritance not supported")
            if (firstField) firstField = false else ret.append(",")
            ret.append(Formatter.EOL)
            ret.append("  class varchar(30)")
        }

        if (asm.parent == null) {
            val pkFields = asm.getPrimaryKey().map { camelToSnake(it.name) }.joinToString(",")
            if (pkFields.isNotEmpty()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(Formatter.EOL)
                ret.append("  PRIMARY KEY ($pkFields)")
            }
        } else {
            val pkFields = asm.parent.getPrimaryKey().map { camelToSnake(it.name) }.joinToString(",")
            if (pkFields.isNotEmpty()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(Formatter.EOL)
                ret.append("  PRIMARY KEY ($pkFields)")
            }
        }

        ret.append("${Formatter.EOL})$END${Formatter.EOL}")

        if (asm.parent != null) {
            ret.append(defineInheritedView(asm))
        }

        return ret.toString()
    }

    override fun format(asm: ASTField, indent: String): String {
        val ret = StringBuilder(indent)
        asm.apply {
            ret.append(camelToSnake(name))
            if (type.isEmpty()) throw RuntimeException("Missing type for ${asm.table.schema.name}.${asm.table.name}.${asm.name}")
            else if (type.startsWith("enum(")) ret.append(" enum_${camelToSnake(name)}")
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