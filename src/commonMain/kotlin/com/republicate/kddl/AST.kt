package com.republicate.kddl

abstract  class DBObject(val name : String) {
    abstract fun display(indent: String = "", builder: StringBuilder = StringBuilder()): StringBuilder
}

open class Database(name : String) : DBObject(name) {
    val options = mutableMapOf<String, Option>()
    val schemas = mutableMapOf<String, Schema>()
    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.appendLine("${indent}database $name {")
        for (schema in schemas.values) {
            schema.display("$indent  ", builder)
        }
        builder.appendLine("${indent}}")
        return builder
    }
}

class Schema(val db : Database, name : String) : DBObject(name) {
    val tables = mutableMapOf<String, Table>()
    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.appendLine("${indent}schema $name {")
        for (table in tables.values) {
            table.display("$indent  ", builder)
        }
        builder.appendLine("${indent}}")
        return builder
    }
}

open class Table(val schema : Schema, name : String, val parent : Table? = null, val parentDirection : String = "") : DBObject(name) {

    val fields = mutableMapOf<String, Field>()
    val foreignKeys = mutableListOf<ForeignKey>()
    val children = mutableSetOf<Table>()

    init {
        parent?.children?.add(this) // yeah, I know, leaking out 'this' from ctor... TODO
    }

    fun getPrimaryKey() : Set<Field> = fields.values.filter { it.primaryKey }.toSet()

    fun getOrCreatePrimaryKey() : Set<Field> = fields.values.filter { it.primaryKey }.ifEmpty {
        parent?.getOrCreatePrimaryKey() ?: run {
            val pkName = "$name$suffix"
            val pk = Field(this, pkName, "serial", true, true, true)
            fields[pkName] = pk
            listOf(pk)
        }
    }.toSet()

    fun getMaybeInheritedField(name: String) : Field? {
        var targetTable : Table? = this
        while (targetTable != null) {
            val field = targetTable.fields[name]
            if (field != null) return field
            targetTable = targetTable.parent
        }
        return null
    }

    companion object {
        fun findTable(schema : Schema, tableName : String, targetSchemaName : String = "") : Table {
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

class JoinTable(schema: Schema, val sourceTable: Table, val destTable : Table) : Table(schema, "${sourceTable.name}_${destTable.name}") {

}

val suffix = "_id"

class Field(
        val table : Table,
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
    fun getForeignKeys() : List<ForeignKey> = table.foreignKeys.filter { this in it.fields }
    fun isLinkField() : Boolean = !getForeignKeys().isEmpty()
    fun isImplicitLinkField() : Boolean {
        val fks = getForeignKeys()
        if (fks.size != 1) return false
        val fk = fks[0]
        if (fk.fields.size != 1) return false
        val pk = fk.towards.getOrCreatePrimaryKey().first()
        if (name != pk.name) return false
        if (type !in listOf("integer", pk.type)) return false
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
class ForeignKey(
        val from : Table,
        val fields : Set<Field>,
        val towards : Table,
        val nonNull: Boolean = false,
        val unique: Boolean = false,
        val cascade: Boolean = false,
        val direction: String = ""
) {
    fun isFieldLink() : Boolean {
        if (fields.size != 1) return false
        val fk = fields.first()
        val pk = towards.getOrCreatePrimaryKey().first()
        if (fk.type !in setOf(pk.type, "integer")) return true
        if (fk.default != null) return true
        if (fk.name != pk.name) return true
        return false
    }
}

class Option(
    val name : String,
    val value : String
)

// DSL

fun database(name : String, content: Database.() -> Unit) : Database {
    return Database(name).apply(content)
}

fun Database.schema(name : String, content : Schema.() -> Unit) : Schema {
    return Schema(this, name).also { schemas[name] = it }.apply(content)
}

fun Database.option(name : String, value : String) : Option {
    return Option(name, value).also { options[name] = it }
}

fun Schema.table(name : String, content : Table.() -> Unit) : Table {
    return Table(this, name).also { tables[name] = it }.apply(content)
}

fun Table.field(name : String, type : String, primaryKey: Boolean = false, nonNull : Boolean = true, unique : Boolean = false, default : Any? = null) : Field {
    return Field(this, name, type, primaryKey, nonNull, unique, default).also {fields[name] = it }
}
