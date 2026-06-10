package com.republicate.kddl

import com.republicate.kddl.Formatter.Companion.EOL

abstract class SQLFormatter(val quoted: Boolean, val uppercase: Boolean, val idempotent: Boolean = false): Formatter {

    open val supportsEnums = false
    open val supportsInheritance = false
    open val supportsPartialIndex = false
    open val scopedObjectNames = false
    open fun defineEnum(typeName: String, values: List<String>) = ""
    open fun defineInheritedView(table: ASTTable) = ""
    open fun setSchema(schema: String) = "SET SCHEMA $schema$END"

    // SQL type name for a field's type
    protected fun sqlTypeName(field: ASTField): String = when (val t = field.type) {
        is FieldType.NamedEnum ->
            if (supportsEnums) "enum_${transform(t.enum.name).removeSurrounding(Q)}"
            else enumFallbackType(t.enum.values)
        is FieldType.InlineEnum ->
            if (supportsEnums) "enum_${transform(field.name).removeSurrounding(Q)}"
            else enumFallbackType(t.values)
        is FieldType.Primitive -> mapType(t.name) ?: t.name
    }

    // dialects without native enum support store an enum as a varchar wide enough for its longest label
    private fun enumFallbackType(values: List<String>) = "varchar(${values.maxOf { it.length }})"

    protected open val END = ";${EOL}"
    protected open val Q = if (quoted) "\"" else ""
    val upper = Regex("[A-Z]")
    open fun transform(str: String) =
        if (uppercase) "${Q}${camelToSnake(str).uppercase()}$Q"
        else "${Q}${camelToSnake(str)}$Q"

    private val typeMap = mapOf(
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
            ret.append(it.value.lowercase())
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
        if (!idempotent) {
            ret.append("DROP SCHEMA IF EXISTS $schemaName CASCADE$END")
        }
        ret.append("CREATE SCHEMA${if (idempotent) " IF NOT EXISTS" else ""} $schemaName")
        // incorrect
//        val owner = asm.db.options["owner"]?.value ?: ""
//        if (owner.isNotEmpty()) ret.append(" WITH OWNER ${owner.removeSurrounding("'")}")
        ret.append(END)
        ret.append(setSchema(schemaName))
        if (supportsEnums) {
            // Named enums: emit once per ASTEnum (identity-deduped)
            val namedEnumTypes = mutableSetOf<ASTEnum>()
            val namedEnumDefs = StringBuilder()
            // Inline anonymous enums: one type per field (per-field naming preserved)
            val inlineEnumDefs = StringBuilder()
            for (table in asm.tables.values) {
                for (field in table.fields.values) {
                    when (val t = field.type) {
                        is FieldType.NamedEnum -> {
                            if (namedEnumTypes.add(t.enum)) {
                                val name = "enum_${transform(t.enum.name).removeSurrounding(Q)}"
                                if (namedEnumDefs.isNotEmpty()) namedEnumDefs.append(EOL)
                                namedEnumDefs.append(defineEnum(name, t.enum.values))
                            }
                        }
                        is FieldType.InlineEnum -> {
                            val name = "enum_${transform(field.name).removeSurrounding(Q)}"
                            if (inlineEnumDefs.isNotEmpty()) inlineEnumDefs.append(EOL)
                            inlineEnumDefs.append(defineEnum(name, t.values))
                        }
                        else -> { /* skip */ }
                    }
                }
            }
            ret.append(namedEnumDefs)
            if (namedEnumDefs.isNotEmpty() && inlineEnumDefs.isNotEmpty()) ret.append(EOL)
            ret.append(inlineEnumDefs)
        }
        ret.append(EOL)
        ret.append(
            asm.tables.values.joinToString(separator = EOL) {
                format(it, indent)
            }
        )
        asm.tables.values.flatMap{ it.foreignKeys }/*.filter {
            !it.isFieldLink()
        }*/.forEach {
            ret.append(format(it, indent))
        }

        // foreign key from child to parent table
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

        if (asm.parent != null) {
            if (!supportsInheritance) throw Error("inheritance not supported")
            tableName = transform("base_${asm.name}")
        }

        ret.append("CREATE TABLE${if (idempotent) " IF NOT EXISTS" else ""} $tableName (")
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

        // unique constraint groups (conditional ones become partial unique indexes below)
        for (index in asm.indices.filter { it.unique && it.condition == null }) {
            val indexCols = index.fields.joinToString(", ") { transform(it.name) }
            if (firstField) firstField = false else ret.append(",")
            ret.append(EOL)
            ret.append("  UNIQUE ($indexCols)")
        }

        if (asm.parent == null) {
            val pkFields = asm.getPrimaryKey().joinToString(", ") { transform(it.name) }
            if (pkFields.isNotEmpty()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(EOL)
                ret.append("  PRIMARY KEY ($pkFields)")
            }
        } else {
            val pkFields = asm.parent.getPrimaryKey().joinToString(",") { transform(it.name) }
            if (pkFields.isNotEmpty()) {
                if (firstField) firstField = false else ret.append(",")
                ret.append(EOL)
                ret.append("  PRIMARY KEY ($pkFields)")
            }
        }

        ret.append("${EOL})$END${EOL}")

        if (!supportsPartialIndex && asm.indices.any { it.condition != null }) {
            throw SemanticException("partial indexes not supported by this dialect: table ${asm.name}")
        }

        // non-unique indexes: constraint groups plus singleton '+' fields,
        // then conditional unique groups as partial unique indexes
        val indexes = asm.indices.filter { !it.unique }.toMutableList()
        asm.fields.values.filter { it.indexed && !it.primaryKey }.forEach { field ->
            if (indexes.none { it.fields == listOf(field) }) indexes.add(ASTIndex(asm, listOf(field), false))
        }
        indexes.addAll(asm.indices.filter { it.unique && it.condition != null })
        for (index in indexes) {
            val rawName = "${asm.name}_${index.fields.joinToString("_") { it.name }}${if (index.unique) "_uidx" else "_idx"}"
            val indexCols = index.fields.joinToString(", ") { transform(it.name) }
            ret.append("CREATE ${if (index.unique) "UNIQUE " else ""}INDEX${if (idempotent) " IF NOT EXISTS" else ""} ${transform(rawName)} ON $tableName ($indexCols)")
            index.condition?.let { ret.append(" WHERE ${sqlCondition(it)}") }
            ret.append(END)
        }
        if (indexes.isNotEmpty()) ret.append(EOL)

        if (asm.parent != null) {
            ret.append(defineInheritedView(asm))
        }

        return ret.toString()
    }

    protected fun sqlCondition(cond: ASTCondition): String = when (cond.op) {
        ASTCondition.Op.IS_TRUE -> transform(cond.field.name)
        ASTCondition.Op.IS_FALSE -> "NOT ${transform(cond.field.name)}"
        ASTCondition.Op.IS_NULL -> "${transform(cond.field.name)} IS NULL"
        ASTCondition.Op.IS_NOT_NULL -> "${transform(cond.field.name)} IS NOT NULL"
    }

    @Suppress("UNCHECKED_CAST")
    override fun format(asm: ASTField, indent: String): String {
        val ret = StringBuilder(indent)
        asm.apply {
            ret.append(transform(name))
            ret.append(" ${sqlTypeName(asm)}")
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
                    if (default.contains('(') && default.contains(')')) {
                        // TODO - if default contains labels, then use GENERATED BY, else DEFAULT
                        // WIP
                        if (default.startsWith("concat")) {
                            ret.append(" GENERATED ALWAYS AS ( $default ) STORED") // TODO this is specific to postgres
                        }
                        else {
                            ret.append(" DEFAULT $default")
                        }
                    }
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
            if (scopedObjectNames) transform(asm.fields.joinToString("_") { it.name }.removeSuffix(keySuffix))
            else "$Q${transform(src.name).removeSurrounding(Q)}_${transform(asm.fields.first().name.removeSuffix(keySuffix)).removeSurrounding(Q)}_fk$Q"
        ret.append("ALTER TABLE $srcName")
        ret.append(" ADD CONSTRAINT $fkName")
        ret.append(" FOREIGN KEY (${
            asm.fields.joinToString(",") {
                transform(it.name)
            }
        })")
        ret.append(" REFERENCES ")

        if (asm.towards.schema.name != src.schema.name) {
            ret.append("${transform(asm.towards.schema.name)}.")
        }
        val dstName =
            if (asm.towards.parent == null) transform(asm.towards.name)
            else "${Q}base_${transform(asm.towards.name).removeSurrounding(Q)}$Q"
        ret.append("$dstName (${
            asm.towards.getOrCreatePrimaryKey().joinToString(",") {
                transform(it.name)
            }
        })")
        if (asm.cascade) {
            ret.append(" ON DELETE CASCADE")
        }
        ret.append(END)
        return ret.toString()
    }
}
