package com.republicate.kddl

import com.republicate.kddl.parser.kddlLexer
import com.republicate.kddl.parser.kddlParser
import org.antlr.v4.kotlinruntime.*
import org.antlr.v4.kotlinruntime.tree.Tree
import org.antlr.v4.kotlinruntime.tree.Trees

fun parse(ddl: CharStream, errorListener: ANTLRErrorListener = ConsoleErrorListener()): ASTDatabase {
    return parse(ddl, null, mutableSetOf(), errorListener)
}

fun parse(
    ddl: CharStream,
    basePath: String?,
    loadedFiles: MutableSet<String>,
    errorListener: ANTLRErrorListener = ConsoleErrorListener()
): ASTDatabase {
    val lexer = kddlLexer(ddl)
    val tokenStream = CommonTokenStream(lexer)
    val parser = kddlParser(tokenStream)
    parser.addErrorListener(errorListener)
    val root = parser.database()
    return buildAst(root, basePath, loadedFiles, errorListener)
}

// WIP
private fun String.returnType(): String = when (this) {
    "concat" -> "varchar"
    "uuidv7" -> "uuid"
    else -> throw SemanticException("return type not known for function: ${this}")
}

fun buildAst(astDatabase: kddlParser.DatabaseContext): ASTDatabase =
    buildAst(astDatabase, null, mutableSetOf(), ConsoleErrorListener())

fun buildAst(
    astDatabase: kddlParser.DatabaseContext,
    basePath: String?,
    loadedFiles: MutableSet<String>,
    errorListener: ANTLRErrorListener
): ASTDatabase {
    // database
    val database = ASTDatabase(astDatabase.name!!.text!!)

    // Process includes first
    for (astInclude in astDatabase.include_stmt()) {
        val rawPath = astInclude.path!!.text!!.removeSurrounding("'")
        val includePath = if (basePath != null) "$basePath/$rawPath" else rawPath
        val normalizedPath = Utils.normalizePath(includePath)

        // Check for circular dependency
        if (normalizedPath in loadedFiles) {
            throw SemanticException("circular include detected: $normalizedPath")
        }
        loadedFiles.add(normalizedPath)

        // Record the include in AST
        database.includes.add(ASTInclude(rawPath))

        // Parse included file
        val includeStream = Utils.getFile(includePath)
        val includeBasePath = Utils.parentPath(includePath)
        val includedDb = parse(includeStream, includeBasePath, loadedFiles, errorListener)

        // Merge included database into this one
        mergeDatabase(database, includedDb)
    }
    for (astSchema in astDatabase.schema()) {
        // schema
        val schema = ASTSchema(database, astSchema.name!!.text!!)
        database.schemas[schema.name] = schema
        // enums (must be parsed before tables to allow references)
        for (astEnum in astSchema.enum_decl()) {
            val enumName = astEnum.name!!.text!!
            val values = astEnum.enum_value().map {
                it.STRING()?.text?.removeSurrounding("'") ?: it.LABEL()!!.text!!
            }
            val enum = ASTEnum(schema, enumName, values)
            schema.enums[enumName] = enum
        }
        for (astTable in astSchema.table()) {
            // table
            val table = ASTTable(schema, astTable.name!!.text!!, database.resolveTable(schema, astTable.par), astTable.direction()?.text ?: "")
            schema.tables[table.name] = table
            for (astField in astTable.field()) {
                // field
                val fieldName = astField.name!!.text
                val reference = database.resolveTable(schema, astField.reference)
                val pk = astField.pk != null
                val nonNull = astField.optional == null
                val unique = astField.unique != null
                val indexed = astField.indexed != null
                val field = if (reference == null) {
                    // standard field
                    var default: Any? = null
                    val astDefault = astField.default()?.expression()
                    if (astDefault != null) {
                        default = when {
                            astDefault.NULL() != null -> null
                            astDefault.STRING() != null -> astDefault.text.removeSurrounding("'")
                            astDefault.boolean() != null -> astDefault.text.toBooleanStrict()
                            astDefault.number() != null -> astDefault.text.toDouble()
                            astDefault.function() != null -> astDefault.text /* TODO */
                            else -> throw SemanticException("invalid default value: ${astDefault.text}")
                        }
                    }
                    var type: FieldType? = null
                    val astType = astField.type()
                    if (astType != null) {
                        val enumRef = astType.enum_ref?.text
                        if (enumRef != null) {
                            val enum = schema.enums[enumRef]
                                ?: throw SemanticException("enum not found: $enumRef")
                            type = FieldType.NamedEnum(enum)
                        } else if (astType.enum_value().isNotEmpty()) {
                            val values = astType.enum_value().map {
                                it.STRING()?.text?.removeSurrounding("'") ?: it.LABEL()!!.text!!
                            }
                            type = FieldType.InlineEnum(values)
                        } else {
                            type = FieldType.Primitive(astType.text)
                        }
                    }
                    if (type == null) {
                        // This section is a work in progress
                        if (astDefault?.STRING() != null) type = FieldType.Primitive("varchar")
                        else if (astDefault?.function() != null) astDefault.function()?.LABEL()?.text?.returnType()?.let { type = FieldType.Primitive(it) }
                        else if (astDefault?.boolean() != null) type = FieldType.Primitive("boolean")
                        // else... inspect number type... ?
                    }
                    if (type == null) {
                        throw SemanticException("type not found for field: ${astField.text}")
                    }
                    val alias = astField.alias?.text
                    ASTField(table, fieldName, type, pk, nonNull, unique, indexed, default, alias)
                } else {
                    // link field
                    val refPk = reference.getOrCreatePrimaryKey()
                    val cascade = astField.CASCADE() != null
                    val direction = astField.direction()?.text ?: ""
                    val fieldType: FieldType = refPk.first().type.let {
                        if (it is FieldType.Primitive && it.name == "serial") FieldType.Primitive("int") else it
                    }
                    ASTField(table, fieldName, fieldType, pk, nonNull, unique)
                        .also {
                            val fk = ASTForeignKey(table, setOf(it), reference, nonNull, unique, cascade, direction)
                            table.foreignKeys.add(fk)
                        }
                }
                table.fields[field.name] = field
            }
            // constraint groups, after all fields are known
            for (astConstraint in astTable.constraint()) {
                val unique = astConstraint.unique != null
                val groupFields = astConstraint.identifier().map {
                    table.fields[it.text] ?: throw SemanticException("field not found in constraint: ${table.name}.${it.text}")
                }
                table.getOrCreateIndex(groupFields, unique)
            }
        }
        for (astLink in astSchema.link()) {
            processLinkChain(astLink, database, schema)
        }
    }
    // root links
    for (astLink in astDatabase.link()) {
        processLinkChain(astLink, database, null)
    }
    // options
    for (astOption in astDatabase.option()) {
        database.option(astOption.name!!.text!!, astOption.value!!.text!!)
    }
    return database
}

fun processLinkChain(astLink: kddlParser.LinkContext, database: ASTDatabase, defSchema: ASTSchema?) {
    val elements = astLink.linkElement()
    val connectors = astLink.connector()
    val cascade = astLink.CASCADE() != null

    // Iterate through pairs: (element[i], connector[i], element[i+1])
    for (i in connectors.indices) {
        val leftElem = elements[i]
        val rightElem = elements[i + 1]
        val conn = connectors[i]

        val left = database.resolveTable(defSchema, leftElem.ref) ?: throw SemanticException("table not found: ${leftElem.text}")
        val right = database.resolveTable(defSchema, rightElem.ref) ?: throw SemanticException("table not found: ${rightElem.text}")

        processLinkPair(left, leftElem.optional != null, conn, right, rightElem.optional != null, cascade)
    }
}

fun processLinkPair(
    left: ASTTable,
    leftOptional: Boolean,
    conn: kddlParser.ConnectorContext,
    right: ASTTable,
    rightOptional: Boolean,
    cascade: Boolean
) {
    val leftMult = conn.left_mult != null || conn.right_single != null
    val rightMult = conn.right_mult != null || conn.left_single != null
    val leftNoNull = !leftOptional
    val rightNoNull = !rightOptional

    if (leftMult && rightMult) {
        // Many-to-many: create join table
        val linkTable = JoinTable(left.schema, left, right)
        left.schema.tables[linkTable.name] = linkTable
        arrayOf(left, right).forEach {
            val pk = it.getOrCreatePrimaryKey()
            val fkFields = pk.map {
                val type: FieldType = it.type.let { t ->
                    if (t is FieldType.Primitive && t.name == "serial") FieldType.Primitive("int") else t
                }
                val fkField = ASTField(linkTable, it.name, type, false, true, false)
                linkTable.fields[it.name] = fkField
                fkField
            }.toSet()
            val fk = ASTForeignKey(linkTable, fkFields, it, true, false, true)
            linkTable.foreignKeys.add(fk)
        }
    } else if (leftMult || rightMult) {
        // One-to-many
        val pkTable = if (leftMult) right else left
        val fkTable = if (leftMult) left else right
        val nonNull = if (leftMult) rightNoNull else leftNoNull
        val pk = pkTable.getOrCreatePrimaryKey()
        val fkFields = pk.map {
            var fkField = fkTable.getMaybeInheritedField(it.name)
            if (fkField == null || fkField.primaryKey) {
                // need to create an implicit field
                val fieldName =
                    if (fkField == null) it.name
                    else "${pkTable.name.withoutCapital()}${it.name.withCapital()}"
                val type: FieldType = it.type.let { t ->
                    if (t is FieldType.Primitive && t.name == "serial") FieldType.Primitive("int") else t
                }
                fkField = ASTField(fkTable, fieldName, type, false, nonNull, false)
                fkTable.fields[fieldName] = fkField
            }
            val fkT = fkField.type
            val pkT = it.type
            if (fkT != pkT && pkT is FieldType.Primitive && pkT.name == "serial"
                && (fkT !is FieldType.Primitive || fkT.name !in arrayOf("int", "long")))
                throw SemanticException("link ${fkTable.name} -> ${pkTable.name}: incompatible fk/pk field types")
            fkField
        }.toSet()
        val fk = ASTForeignKey(from=fkTable, fields=fkFields, towards=pkTable, nonNull=nonNull, false, cascade)
        fkTable.foreignKeys.add(fk)
    }
}

fun Tree.format(parser: Parser, indent: Int = 0): String = buildString {
    val tree = this@format
    val prefix = "  ".repeat(indent)
    append(prefix)
    append(Trees.getNodeText(tree, parser))
    if (tree.childCount != 0) {
        append(" (\n")
        for (i in 0 until tree.childCount) {
            append(tree.getChild(i)!!.format(parser, indent + 1))
            append("\n")
        }
        append(prefix).append(")")
    }
}

fun ASTDatabase.resolveTable(astTable: kddlParser.QualifiedContext?) = resolveTable(null, astTable)

fun ASTDatabase.resolveTable(defSchema: ASTSchema?, astTable: kddlParser.QualifiedContext?) : ASTTable? {
    if (astTable == null) return null
    val schema = astTable.ref_schema?.text?.let { schemas[it] } ?: defSchema ?: throw SemanticException("no schema")
    val name = astTable.name!!.text!!
    val table = schema.tables[name] ?: throw SemanticException("table not found: ${schema.name}.$name")
    return table
}

class KDDLFormatter: Formatter {
    override fun format(asm: ASTDatabase, indent: String) = asm.display(indent).toString()
    override fun format(asm: ASTSchema, indent: String) = asm.display(indent).toString()
    override fun format(asm: ASTTable, indent: String) = asm.display(indent).toString()
    override fun format(asm: ASTField, indent: String) = asm.display(indent).toString()
    override fun format(asm: ASTForeignKey, indent: String) = throw NotImplementedError("TODO")
}

/**
 * Merge included database schemas into the target database.
 * Schemas with the same name are merged (enums and tables combined).
 * Conflicts (same-named enum/table in same schema) throw an error.
 */
private fun mergeDatabase(target: ASTDatabase, source: ASTDatabase) {
    for ((schemaName, sourceSchema) in source.schemas) {
        val targetSchema = target.schemas[schemaName]
        if (targetSchema == null) {
            // Create new schema in target with same name, linked to target database
            val newSchema = ASTSchema(target, schemaName)
            target.schemas[schemaName] = newSchema
            // Copy enums (recreate with new schema reference)
            for ((enumName, srcEnum) in sourceSchema.enums) {
                val newEnum = ASTEnum(newSchema, enumName, srcEnum.values)
                newSchema.enums[enumName] = newEnum
            }
            // Copy tables (recreate with new schema reference)
            for ((tableName, srcTable) in sourceSchema.tables) {
                copyTable(srcTable, newSchema)
            }
        } else {
            // Merge into existing schema
            for ((enumName, srcEnum) in sourceSchema.enums) {
                if (enumName in targetSchema.enums) {
                    throw SemanticException("duplicate enum in include: $schemaName.$enumName")
                }
                val newEnum = ASTEnum(targetSchema, enumName, srcEnum.values)
                targetSchema.enums[enumName] = newEnum
            }
            for ((tableName, srcTable) in sourceSchema.tables) {
                if (tableName in targetSchema.tables) {
                    throw SemanticException("duplicate table in include: $schemaName.$tableName")
                }
                copyTable(srcTable, targetSchema)
            }
        }
    }
    // Merge options
    for ((key, value) in source.options) {
        if (key !in target.options) {
            target.options[key] = value
        }
    }
}

/**
 * Deep copy a table into a new schema, preserving fields and foreign keys.
 */
private fun copyTable(srcTable: ASTTable, targetSchema: ASTSchema): ASTTable {
    // Handle parent table reference (must be in target schema or already copied)
    val parent = srcTable.parent?.let {
        targetSchema.tables[it.name]
            ?: throw SemanticException("parent table not found during include: ${it.name}")
    }
    val newTable = ASTTable(targetSchema, srcTable.name, parent, srcTable.parentDirection)
    targetSchema.tables[newTable.name] = newTable

    // Copy fields
    for ((fieldName, srcField) in srcTable.fields) {
        val newType: FieldType = when (val t = srcField.type) {
            is FieldType.NamedEnum -> {
                val rebound = targetSchema.enums[t.enum.name]
                    ?: throw SemanticException("enum not found during include copy: ${t.enum.name}")
                FieldType.NamedEnum(rebound)
            }
            else -> t
        }
        val newField = ASTField(
            newTable, fieldName, newType,
            srcField.primaryKey, srcField.nonNull, srcField.unique,
            srcField.indexed, srcField.default, srcField.alias
        )
        newTable.fields[fieldName] = newField
    }

    // Copy foreign keys (resolve towards table in target database)
    for (srcFk in srcTable.foreignKeys) {
        val towardsSchema = targetSchema.db.schemas[srcFk.towards.schema.name]
            ?: throw SemanticException("schema not found for FK: ${srcFk.towards.schema.name}")
        val towardsTable = towardsSchema.tables[srcFk.towards.name]
            ?: throw SemanticException("table not found for FK: ${srcFk.towards.name}")
        val newFields = srcFk.fields.map { newTable.fields[it.name]!! }.toSet()
        val newFk = ASTForeignKey(
            newTable, newFields, towardsTable,
            srcFk.nonNull, srcFk.unique, srcFk.cascade, srcFk.direction
        )
        newTable.foreignKeys.add(newFk)
    }

    // Copy constraint groups
    for (srcIndex in srcTable.indices) {
        newTable.getOrCreateIndex(srcIndex.fields.map { newTable.fields[it.name]!! }, srcIndex.unique)
    }

    return newTable
}
