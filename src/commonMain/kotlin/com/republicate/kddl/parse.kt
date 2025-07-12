package com.republicate.kddl

import com.republicate.kddl.parser.kddlLexer
import com.republicate.kddl.parser.kddlParser
import org.antlr.v4.kotlinruntime.*
import org.antlr.v4.kotlinruntime.tree.Tree
import org.antlr.v4.kotlinruntime.tree.Trees

fun parse(ddl: CharStream, errorListener: ANTLRErrorListener = ConsoleErrorListener()): ASTDatabase {
    val lexer = kddlLexer(ddl)
    val tokenStream = CommonTokenStream(lexer)
    val parser = kddlParser(tokenStream)
    parser.addErrorListener(errorListener)
    val root = parser.database()
    return buildAst(root)
}

// WIP
private fun String.returnType(): String = when (this) {
    "concat" -> "varchar"
    "uuidv7" -> "uuid"
    else -> throw SemanticException("return type not known for function: ${this}")
}

fun buildAst(astDatabase : kddlParser.DatabaseContext) : ASTDatabase {
    // database
    val database = ASTDatabase(astDatabase.name!!.text!!)
    for (astSchema in astDatabase.schema()) {
        // schema
        val schema = ASTSchema(database, astSchema.name!!.text!!)
        database.schemas[schema.name] = schema
        for (astTable in astSchema.table()) {
            // table
            val table = ASTTable(schema, astTable.name!!.text!!, database.resolveTable(schema, astTable.par), astTable.direction()?.text ?: "")
            schema.tables[table.name] = table
            for (astField in astTable.field()) {
                // field
                val fieldName = astField.name!!.text!!
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
                    var type = astField.type()?.text
                    if (type == null) {
                        // This section is a work in progress
                        if (astDefault?.STRING() != null) type = "varchar"
                        else if (astDefault?.function() != null) type = astDefault.function()?.LABEL()?.text?.returnType()
                        else if (astDefault?.boolean() != null) type = "boolean"
                        // else... inspect number type... ?
                    }
                    if (type == null) {
                        throw SemanticException("type not found for field: ${astField.text}")
                    }
                    ASTField(table, fieldName, type, pk, nonNull, unique, indexed, default)
                } else {
                    // link field
                    val refPk = reference.getOrCreatePrimaryKey()
                    val cascade = astField.CASCADE() != null
                    val direction = astField.direction()?.text ?: ""
                    val fieldType = refPk.first().type.let { if (it == "serial") "int" else it }
                    ASTField(table, fieldName, fieldType, pk, nonNull, unique)
                        .also {
                            val fk = ASTForeignKey(table, setOf(it), reference, nonNull, unique, cascade, direction)
                            table.foreignKeys.add(fk)
                        }
                }
                table.fields[field.name] = field
            }
        }
        for (astLink in astSchema.link()) {
            val left = database.resolveTable(schema, astLink.left) ?: throw SemanticException("left table not found") // should not happen
            val right = database.resolveTable(schema, astLink.right) ?: throw SemanticException("right table not found") // should not happen
            val leftMult = astLink.left_mult != null || astLink.right_single != null // || astLink.left_single == null
            val rightMult = astLink.right_mult != null || astLink.left_single != null // || astLink.right_single == null
            val leftNoNull = astLink.left_optional == null
            val rightNoNull = astLink.right_optional == null
            if (leftMult && rightMult) {
                val linkTable = ASTTable(left.schema, "${left.name}_${right.name}")
                left.schema.tables[linkTable.name] = linkTable
                arrayOf(left, right).forEach {
                    val pk = it.getOrCreatePrimaryKey()
                    val fkFields = pk.map {
                        val fkField = ASTField(linkTable, it.name, it.type.let { if (it == "serial") "int" else it}, false, true, false)
                        linkTable.fields[it.name] = fkField
                        fkField
                    }.toSet()
                    val fk = ASTForeignKey(linkTable, fkFields, it, true, false, true)
                    linkTable.foreignKeys.add(fk)
                }
                schema.tables[linkTable.name] = linkTable
            } else if (leftMult || rightMult) {
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
                        val type = it.type.let { if (it == "serial") "int" else it}
                        fkField = ASTField(fkTable, fieldName, type, false, nonNull, false)
                        fkTable.fields[fieldName] = fkField
                    }
                    if (fkField.type != it.type && it.type == "serial" && fkField.type !in arrayOf("int", "long"))
                        throw SemanticException("link ${fkTable.name} -> ${pkTable.name}: incompatible fk/pk field types")
                    fkField
                }.toSet()
                val cascade = astLink.CASCADE() != null
                val fk = ASTForeignKey(from=fkTable, fields=fkFields, towards=pkTable, nonNull=nonNull, false, cascade)
                fkTable.foreignKeys.add(fk)
            }
        }
    }
    return database
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
