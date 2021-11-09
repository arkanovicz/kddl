package com.republicate.kddl

abstract  class DBObject(val name : String) {
    abstract fun display(indent: String = "")
}

open class Database(name : String) : DBObject(name) {
    val options = mutableMapOf<String, Option>()
    val schemas = mutableMapOf<String, Schema>()
    override fun display(indent: String) {
        println("${indent}database $name {")
        for (schema in schemas.values) {
            schema.display("$indent  ")
        }
        println("${indent}}")
    }
}

class Schema(val db : Database, name : String) : DBObject(name) {
    val tables = mutableMapOf<String, Table>()
    override fun display(indent: String) {
        println("${indent}schema $name {")
        for (table in tables.values) {
            table.display("$indent  ")
        }
        println("${indent}}")
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
            val pk = Field(this, pkName, "serial", true)
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

    override fun display(indent: String) {
        print("${indent}table $name")
        if (parent != null) {
            print(" : ")
            if (parent.schema.name != schema.name) {
                print("${parent.schema.name}.")
            }
            print(parent.name)
        }
        println(" {")
        for (field in fields.values) {
            field.display("$indent  ")
        }
        println("${indent}}")
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

    override fun display(indent: String) {
        print(indent)
        if (primaryKey) print('*')
        else if (unique) print('!')
        print(name)
        val fk = getForeignKeys().firstOrNull()
        if (fk != null) {
            print(" -> ")
            if (fk.towards.schema.name != table.schema.name) {
                print("${fk.towards.schema.name}.")
            }
            print(fk.towards.name)
            if (!nonNull) print('?')
        } else {
            print(" $type")
            if (!nonNull) print('?')
            if (default != null) {
                print(" = $default")
            }
        }
        println()
    }
}

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
