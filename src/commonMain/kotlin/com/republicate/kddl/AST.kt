package com.republicate.kddl

abstract  class DBObject(val name : String) {
    abstract fun display(indent: String = "", builder: StringBuilder = StringBuilder()): StringBuilder
}

open class ASTDatabase(name : String) : DBObject(name) {
    val options = mutableMapOf<String, Option>()
    val schemas = mutableMapOf<String, ASTSchema>()
    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.appendLine("${indent}database $name {")
        for (schema in schemas.values) {
            schema.display("$indent  ", builder)
        }
        builder.appendLine("${indent}}")
        return builder
    }
}

class ASTSchema(val db : ASTDatabase, name : String) : DBObject(name) {
    val tables = mutableMapOf<String, ASTTable>()
    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.appendLine("${indent}schema $name {")
        for (table in tables.values) {
            table.display("$indent  ", builder)
        }
        builder.appendLine("${indent}}")
        return builder
    }
}

open class ASTTable(val schema : ASTSchema, name : String, val parent : ASTTable? = null, val parentDirection : String = "") : DBObject(name) {

    val fields = mutableMapOf<String, ASTField>()
    val foreignKeys = mutableListOf<ASTForeignKey>()
    val children = mutableSetOf<ASTTable>()

    init {
        parent?.children?.add(this) // yeah, I know, leaking out 'this' from ctor... TODO
    }

    fun getPrimaryKey() : Set<ASTField> = fields.values.filter { it.primaryKey }.toSet()

    fun getOrCreatePrimaryKey() : Set<ASTField> = fields.values.filter { it.primaryKey }.ifEmpty {
        parent?.getOrCreatePrimaryKey() ?: run {
            val pkName = "$name$suffix"
            val pk = ASTField(this, pkName, "serial", true, true, true)
            fields[pkName] = pk
            listOf(pk)
        }
    }.toSet()

    fun getMaybeInheritedField(name: String) : ASTField? {
        var targetTable : ASTTable? = this
        while (targetTable != null) {
            val field = targetTable.fields[name]
            if (field != null) return field
            targetTable = targetTable.parent
        }
        return null
    }

    companion object {
        fun findTable(schema : ASTSchema, tableName : String, targetSchemaName : String = "") : ASTTable {
            val targetSchema =
                if (targetSchemaName.isEmpty()) schema
                else schema.db.schemas.getOrElse(targetSchemaName) { // CB TODO see if better to return null
                    throw RuntimeException("schema $targetSchemaName not found")
                }
            return targetSchema.tables.getOrElse(tableName) {
                throw RuntimeException("table ${targetSchemaName} not found")
            }
        }
    }

    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.append("${indent}table $name")
        if (parent != null) {
            builder.append(" : ")
            if (parent.schema.name != schema.name) {
                builder.append("${parent.schema.name}.")
            }
            builder.append(parent.name)
        }
        builder.appendLine(" {")
        for (field in fields.values) {
            field.display("$indent  ", builder)
        }
        builder.appendLine("${indent}}")
        return builder
    }
}

class JoinTable(schema: ASTSchema, val sourceTable: ASTTable, val destTable : ASTTable) : ASTTable(schema, "${sourceTable.name}_${destTable.name}") {

}

val suffix = "_id"

class ASTField(
    val table : ASTTable,
    name : String,
    val type : String,
    val primaryKey: Boolean = false,
    val nonNull: Boolean = true,
    val unique : Boolean = false,
    val default : Any? = null,

    ) : DBObject(name) {
    companion object {
        fun isTextType(type: String): Boolean {
            return type.startsWith("varchar", true) || type == "char" || type == "text" || type == "clob"
        }
    }
    fun isDefaultKey() : Boolean {
        return primaryKey && type == "serial" && name == "${table.name}$suffix" // TODO - handle suffix
    }
    fun getForeignKeys() : List<ASTForeignKey> = table.foreignKeys.filter { this in it.fields }
    fun isLinkField() : Boolean = !getForeignKeys().isEmpty()
    fun isImplicitLinkField() : Boolean {
        val fks = getForeignKeys()
        if (fks.size != 1) return false
        val fk = fks[0]
        if (fk.fields.size != 1) return false
        val pk = fk.towards.getOrCreatePrimaryKey().first()
        if (name != pk.name) return false
        if (type !in listOf("int", "integer", "long", pk.type)) return false
        return true
    }

    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.append(indent)
        if (primaryKey) builder.append('*')
        else if (unique) builder.append('!')
        builder.append(name)
        val fk = getForeignKeys().firstOrNull()
        if (fk != null) {
            builder.append(" -> ")
            if (fk.towards.schema.name != table.schema.name) {
                builder.append("${fk.towards.schema.name}.")
            }
            builder.append(fk.towards.name)
            if (!nonNull) builder.append('?')
            if (fk.cascade) builder.append(" cascade")
            if (fk.direction.isNotEmpty()) builder.append(" ${fk.direction}")
        } else {
            builder.append(" $type")
            if (!nonNull) builder.append('?')
            if (default != null) {
                if (isTextType(type) && !type.startsWith("'")) builder.append(" = '$default'")
                else builder.append(" = $default")
            }
        }
        builder.appendLine()
        return builder
    }
}

// CB TODO - for now we don't store pk fields, hoping that it's either a single field PK or that fields are named the same
// CB TODO - we consider "cascade" but not "set null"
class ASTForeignKey(
    val from : ASTTable,
    val fields : Set<ASTField>,
    val towards : ASTTable,
    val nonNull: Boolean = false,
    val unique: Boolean = false,
    val cascade: Boolean = false,
    val direction: String = ""
) {
    fun isFieldLink() : Boolean {
        if (fields.size != 1) return false
        val fk = fields.first()
        val pk = towards.getOrCreatePrimaryKey().first()
        if (fk.default != null) return false
        if (fk.name != pk.name && fk.name != "${pk.table.name.decapitalize()}${pk.name.capitalize()}") return false
        if (pk.type != fk.type || pk.type == "serial" && fk.type !in setOf("int", "integer", "long")) return false
        return true
    }
}

class Option(
    val name : String,
    val value : String
)

// DSL

fun database(name : String, content: ASTDatabase.() -> Unit) : ASTDatabase {
    return ASTDatabase(name).apply(content)
}

fun ASTDatabase.schema(name : String, content : ASTSchema.() -> Unit) : ASTSchema {
    return ASTSchema(this, name).also { schemas[name] = it }.apply(content)
}

fun ASTDatabase.option(name : String, value : String) : Option {
    return Option(name, value).also { options[name] = it }
}

fun ASTSchema.table(name : String, content : ASTTable.() -> Unit) : ASTTable {
    return ASTTable(this, name).also { tables[name] = it }.apply(content)
}

fun ASTTable.field(name : String, type : String, primaryKey: Boolean = false, nonNull : Boolean = true, unique : Boolean = false, default : Any? = null) : ASTField {
    return ASTField(this, name, type, primaryKey, nonNull, unique, default).also {fields[name] = it }
}
