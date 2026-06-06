package com.republicate.kddl

abstract  class DBObject(val name : String) {
    abstract fun display(indent: String = "", builder: StringBuilder = StringBuilder()): StringBuilder
}

class ASTInclude(val path: String) : DBObject(path) {
    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.appendLine("${indent}include '$path'")
        return builder
    }
}

open class ASTDatabase(name : String) : DBObject(name) {
    val includes = mutableListOf<ASTInclude>()
    val options = mutableMapOf<String, String>()
    val schemas = mutableMapOf<String, ASTSchema>()
    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        for (include in includes) {
            include.display(indent, builder)
        }
        builder.appendLine("${indent}database $name {")
        for (schema in schemas.values) {
            schema.display("$indent  ", builder)
        }
        builder.appendLine("${indent}}")
        return builder
    }
}

class ASTEnum(val schema: ASTSchema, name: String, val values: List<String>) : DBObject(name) {
    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.append("${indent}enum $name(")
        builder.append(values.joinToString(", ") { "'$it'" })
        builder.appendLine(")")
        return builder
    }
}

class ASTSchema(val db : ASTDatabase, name : String) : DBObject(name) {
    val enums = mutableMapOf<String, ASTEnum>()
    val tables = mutableMapOf<String, ASTTable>()
    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.appendLine("${indent}schema $name {")
        for (enum in enums.values) {
            enum.display("$indent  ", builder)
        }
        // Build chains from relations
        val chains = buildRelationChains()
        val linksInChains = chains.flatMap { it.links }.toSet()

        // Output all non-JoinTables, suppressing implicit link fields that are in chains
        for (table in tables.values) {
            if (table !is JoinTable) {
                table.display("$indent  ", builder, linksInChains)
            }
        }
        // Output chains
        for (chain in chains) {
            chain.display("$indent  ", builder)
        }
        builder.appendLine("${indent}}")
        return builder
    }

    // Represents a link between two tables
    private data class RelationLink(
        val from: ASTTable,
        val to: ASTTable,
        val fromOptional: Boolean,
        val toOptional: Boolean,
        val isManyToMany: Boolean,  // true for *--*, false for --*
        val fk: ASTForeignKey?,     // the FK involved (null for many-to-many from side)
        val joinTable: JoinTable?   // non-null for many-to-many
    )

    // Represents a chain of relations
    private data class RelationChain(
        val elements: List<Pair<ASTTable, Boolean>>,  // table and whether it's optional
        val connectors: List<Pair<Boolean, Boolean>>, // (leftMult, rightMult) for each connector
        val links: Set<ASTForeignKey>,                // FKs involved (for suppression)
        val tables: Set<ASTTable>                     // JoinTables involved (for suppression)
    ) {
        fun display(indent: String, builder: StringBuilder) {
            builder.append(indent)
            for (i in elements.indices) {
                val (table, optional) = elements[i]
                builder.append(table.name)
                if (optional) builder.append('?')
                if (i < connectors.size) {
                    val (leftMult, rightMult) = connectors[i]
                    builder.append(' ')
                    if (leftMult) builder.append('*')
                    builder.append("--")
                    if (rightMult) builder.append('*')
                    builder.append(' ')
                }
            }
            builder.appendLine()
        }
    }

    private fun buildRelationChains(): List<RelationChain> {
        // Build adjacency list of relations
        val links = mutableListOf<RelationLink>()

        // Collect many-to-many from JoinTables
        for (table in tables.values) {
            if (table is JoinTable) {
                val fks = table.foreignKeys
                if (fks.size == 2) {
                    val fk1 = fks[0]
                    val fk2 = fks[1]
                    links.add(RelationLink(
                        from = fk1.towards,
                        to = fk2.towards,
                        fromOptional = !fk1.nonNull,
                        toOptional = !fk2.nonNull,
                        isManyToMany = true,
                        fk = null,
                        joinTable = table
                    ))
                }
            }
        }

        // Collect one-to-many from implicit FKs
        for (table in tables.values) {
            if (table is JoinTable) continue
            for (fk in table.foreignKeys) {
                if (fk.isFieldLink() && fk.towards.schema == this) {
                    // This is an implicit link: towards --* from
                    links.add(RelationLink(
                        from = fk.towards,
                        to = fk.from,
                        fromOptional = false,  // PK side is never optional
                        toOptional = !fk.nonNull,
                        isManyToMany = false,
                        fk = fk,
                        joinTable = null
                    ))
                }
            }
        }

        if (links.isEmpty()) return emptyList()

        // Build adjacency maps
        val outgoing = mutableMapOf<ASTTable, MutableList<RelationLink>>()
        val incoming = mutableMapOf<ASTTable, MutableList<RelationLink>>()
        for (link in links) {
            outgoing.getOrPut(link.from) { mutableListOf() }.add(link)
            incoming.getOrPut(link.to) { mutableListOf() }.add(link)
            if (link.isManyToMany) {
                // Many-to-many is bidirectional
                outgoing.getOrPut(link.to) { mutableListOf() }.add(
                    link.copy(from = link.to, to = link.from, fromOptional = link.toOptional, toOptional = link.fromOptional)
                )
                incoming.getOrPut(link.from) { mutableListOf() }.add(
                    link.copy(from = link.to, to = link.from, fromOptional = link.toOptional, toOptional = link.fromOptional)
                )
            }
        }

        // Find all chains using greedy longest-first approach
        val usedLinks = mutableSetOf<RelationLink>()
        val chains = mutableListOf<RelationChain>()

        // Keep finding chains until no more links
        while (usedLinks.size < links.size) {
            val bestChain = findLongestChain(outgoing, usedLinks)
            if (bestChain == null || bestChain.elements.size < 2) break
            chains.add(bestChain)
            usedLinks.addAll(bestChain.links.mapNotNull { fk ->
                links.find { it.fk == fk || (it.joinTable != null && it.joinTable.foreignKeys.contains(fk)) }
            })
            // Also mark the reverse direction for many-to-many
            for (link in links) {
                if (link.joinTable != null && bestChain.tables.contains(link.joinTable)) {
                    usedLinks.add(link)
                }
            }
        }

        return chains
    }

    private fun findLongestChain(
        outgoing: Map<ASTTable, List<RelationLink>>,
        usedLinks: Set<RelationLink>
    ): RelationChain? {
        var bestChain: RelationChain? = null

        // Try starting from each table
        for (startTable in tables.values) {
            if (startTable is JoinTable) continue
            val chain = extendChain(startTable, outgoing, usedLinks, mutableSetOf())
            if (chain != null && (bestChain == null || chain.elements.size > bestChain.elements.size)) {
                bestChain = chain
            }
        }

        return bestChain
    }

    private fun extendChain(
        start: ASTTable,
        outgoing: Map<ASTTable, List<RelationLink>>,
        usedLinks: Set<RelationLink>,
        visitedTables: MutableSet<ASTTable>
    ): RelationChain? {
        visitedTables.add(start)

        val availableLinks = outgoing[start]?.filter {
            it !in usedLinks && it.to !in visitedTables
        } ?: emptyList()

        if (availableLinks.isEmpty()) {
            return RelationChain(
                elements = listOf(start to false),
                connectors = emptyList(),
                links = emptySet(),
                tables = emptySet()
            )
        }

        // Try each available link and pick the one that gives longest chain
        var bestResult: RelationChain? = null
        for (link in availableLinks) {
            val subChain = extendChain(link.to, outgoing, usedLinks, visitedTables.toMutableSet())
            if (subChain != null) {
                val newElements = listOf(start to link.fromOptional) +
                    subChain.elements.mapIndexed { i, (t, opt) ->
                        if (i == 0) t to link.toOptional else t to opt
                    }
                // Connector: (leftMult, rightMult)
                // many-to-many: *--* (true, true)
                // one-to-many: --* (false, true) - the "to" side is the "many" side
                val newConnectors = listOf(link.isManyToMany to true) + subChain.connectors
                val newLinks = subChain.links + listOfNotNull(link.fk) +
                    (link.joinTable?.foreignKeys?.toSet() ?: emptySet())
                val newTables = subChain.tables + listOfNotNull(link.joinTable)

                val result = RelationChain(newElements, newConnectors, newLinks, newTables)
                if (bestResult == null || result.elements.size > bestResult.elements.size) {
                    bestResult = result
                }
            }
        }

        return bestResult ?: RelationChain(
            elements = listOf(start to false),
            connectors = emptyList(),
            links = emptySet(),
            tables = emptySet()
        )
    }
}

open class ASTTable(val schema : ASTSchema, name : String, val parent : ASTTable? = null, val parentDirection : String = "") : DBObject(name) {

    val fields = mutableMapOf<String, ASTField>()
    val foreignKeys = mutableListOf<ASTForeignKey>()
    val children = mutableSetOf<ASTTable>()
    val indices = mutableListOf<ASTIndex>()

    init {
        parent?.children?.add(this) // yeah, I know, leaking out 'this' from ctor... TODO
    }

    fun getPrimaryKey() : Set<ASTField> = fields.values.filter { it.primaryKey }.toSet()

    fun getOrCreatePrimaryKey() : Set<ASTField> = fields.values.filter { it.primaryKey }.ifEmpty {
        parent?.getOrCreatePrimaryKey() ?: run {
            val pkName = "$name$keySuffix"
            val pk = ASTField(this, pkName, "serial", true, true, true)
            fields[pkName] = pk
            listOf(pk)
        }
    }.toSet()

    fun getOrCreateIndex(fields: List<ASTField>, unique: Boolean, condition: ASTCondition? = null) : ASTIndex =
        indices.firstOrNull { it.fields == fields && it.unique == unique && it.condition == condition }
            ?: ASTIndex(this, fields, unique, condition).also { indices.add(it) }

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
        return display(indent, builder, emptySet())
    }

    fun display(indent: String, builder: StringBuilder, suppressedLinks: Set<ASTForeignKey>): StringBuilder {
        builder.append("${indent}table $name")
        if (parent != null) {
            builder.append(" : ")
            if (parent.schema.name != schema.name) {
                builder.append("${parent.schema.name}.")
            }
            builder.append(parent.name)
        }
        // Filter out implicit link fields that are in chains
        val displayFields = fields.values.filter { field ->
            val fks = field.getForeignKeys()
            if (fks.isEmpty()) true
            else if (!field.isImplicitLinkField()) true
            else fks.none { it in suppressedLinks }
        }
        if (displayFields.isEmpty() && indices.isEmpty() && parent == null) {
            builder.appendLine(" {}")
        } else {
            builder.appendLine(" {")
            for (field in displayFields) {
                field.display("$indent  ", builder)
            }
            for (index in indices) {
                index.display("$indent  ", builder)
            }
            builder.appendLine("${indent}}")
        }
        return builder
    }
}

class JoinTable(schema: ASTSchema, val sourceTable: ASTTable, val destTable : ASTTable) : ASTTable(schema, "${sourceTable.name}_${destTable.name}") {

}

val keySuffix = "_id"

sealed class FieldType {
    class Primitive(val name: String) : FieldType() {
        val base: String get() = name.substringBefore('(')
        override fun toString() = name
        override fun equals(other: Any?) = other is Primitive && other.name == name
        override fun hashCode() = name.hashCode()
    }
    class InlineEnum(val values: List<String>) : FieldType() {
        override fun toString() = "enum(${values.joinToString(",") { "'$it'" }})"
        override fun equals(other: Any?) = other is InlineEnum && other.values == values
        override fun hashCode() = values.hashCode()
    }
    class NamedEnum(val enum: ASTEnum) : FieldType() {
        override fun toString() = enum.name
        override fun equals(other: Any?) = other is NamedEnum && other.enum === enum
        override fun hashCode() = enum.hashCode()
    }
}

class ASTField(
    val table : ASTTable,
    name : String,
    val type : FieldType,
    val primaryKey: Boolean = false,
    val nonNull: Boolean = true,
    val unique : Boolean = false,
    val indexed: Boolean = false,
    val default : Any? = null,
    val alias : String? = null,
    ) : DBObject(name) {
    constructor(
        table: ASTTable, name: String, type: String,
        primaryKey: Boolean = false, nonNull: Boolean = true, unique: Boolean = false,
        indexed: Boolean = false, default: Any? = null, alias: String? = null,
    ) : this(table, name, FieldType.Primitive(type), primaryKey, nonNull, unique, indexed, default, alias)

    companion object {
        fun isTextType(type: FieldType): Boolean {
            if (type !is FieldType.Primitive) return false
            return type.base.equals("varchar", true) || type.base == "char" || type.base == "text" || type.base == "clob"
        }
    }
    fun isDefaultKey() : Boolean {
        return primaryKey && type is FieldType.Primitive && type.name == "serial" && name == "${table.name}$keySuffix" // TODO - handle suffix
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
        if (type !is FieldType.Primitive) return false
        val pkType = pk.type
        if (pkType !is FieldType.Primitive) return false
        if (type.name !in listOf("int", "integer", "long", pkType.name)) return false
        return true
    }

    override fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.append(indent)
        if (primaryKey) builder.append('*')
        else if (unique) builder.append('!')
        else if (indexed) builder.append('+')
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
            builder.append(" ${type}")
            if (!nonNull) builder.append('?')
            if (default != null) {
                if (isTextType(type)) builder.append(" = '$default'")
                else builder.append(" = $default")
            }
        }
        builder.appendLine()
        return builder
    }
}

// restricted partial-index condition: a single column reference, so identifiers stay transformable per dialect
data class ASTCondition(
    val field: ASTField,
    val op: Op
) {
    enum class Op { IS_TRUE, IS_FALSE, IS_NULL, IS_NOT_NULL }
    override fun toString() = buildString {
        append("where ")
        if (op == Op.IS_FALSE) append("not ")
        append(field.name)
        when (op) {
            Op.IS_NULL -> append(" is null")
            Op.IS_NOT_NULL -> append(" is not null")
            else -> {}
        }
    }
}

// column order matters: fields is ordered
class ASTIndex(
    val table: ASTTable,
    val fields: List<ASTField>,
    val unique: Boolean,
    val condition: ASTCondition? = null
) {
    fun display(indent: String, builder: StringBuilder): StringBuilder {
        builder.append(indent)
        builder.append(if (unique) '!' else '+')
        builder.append(fields.joinToString(", ", "(", ")") { it.name })
        condition?.let { builder.append(' ').append(it) }
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
        if (fk.name != pk.name && fk.name != "${pk.table.name.withoutCapital()}${pk.name.withCapital()}") return false
        val pkT = pk.type
        val fkT = fk.type
        val typesMatch = pkT == fkT || (
            pkT is FieldType.Primitive && pkT.name == "serial"
            && fkT is FieldType.Primitive && fkT.name in setOf("int", "integer", "long")
        )
        if (!typesMatch) return false
        return true
    }
}

// DSL

fun database(name : String, content: ASTDatabase.() -> Unit) : ASTDatabase {
    return ASTDatabase(name).apply(content)
}

fun ASTDatabase.schema(name : String, content : ASTSchema.() -> Unit) : ASTSchema {
    return ASTSchema(this, name).also { schemas[name] = it }.apply(content)
}

fun ASTDatabase.option(name : String, value : String) : Pair<String, String> {
    return Pair(name, value).also { options[name] = value }
}

fun ASTSchema.table(name : String, content : ASTTable.() -> Unit) : ASTTable {
    return ASTTable(this, name).also { tables[name] = it }.apply(content)
}

fun ASTTable.field(name : String, type : String, primaryKey: Boolean = false, nonNull : Boolean = true, unique : Boolean = false, indexed : Boolean = false, default : Any? = null, alias : String? = null) : ASTField {
    return ASTField(this, name, type, primaryKey, nonNull, unique, indexed, default, alias).also {fields[name] = it }
}
