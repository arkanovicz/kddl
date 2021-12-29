package com.republicate.kddl

import com.republicate.kddl.Utils.getFile
import com.republicate.kddl.parser.kddlLexer
import com.republicate.kddl.parser.kddlParser
import com.republicate.kddl.plantuml.PlantUMLFormatter
import com.republicate.kddl.postgresql.PostgreSQLFormatter
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import org.antlr.v4.kotlinruntime.*
import org.antlr.v4.kotlinruntime.tree.Tree
import org.antlr.v4.kotlinruntime.tree.Trees

class SemanticException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

val argParser = ArgParser("kddl")

enum class Format {
    KDDL,
    PLANTUML,
    POSTGRESQL
}

interface Formatter {
    companion object {
        val EOL: String = "\n"
    }
    fun format(asm: Database, indent: String = ""): String
    fun format(asm: Schema, indent: String): String
    fun format(asm: Table, indent: String): String
    fun format(asm: Field, indent: String, ): String
    fun format(asm: ForeignKey, indent: String): String
}

fun main(args: Array<String>) {
    val input by argParser.option(ArgType.String, shortName = "i", description = "input file or url").required()
    val format by argParser.option(ArgType.Choice<Format>(), shortName = "f", description = "output format").required()
    val driver by argParser.option(ArgType.String, shortName = "d", description = "jdbc driver")
    argParser.parse(args)

    val tree = when {
        input.startsWith("jdbc:") -> {
            driver?.let { loadLibrary(it) }
            reverse(input)
        }
        else -> {
            val ddl = Utils.getFile(input)
            parse(ddl)
        }
    }
    val formatter = when (format) {
        Format.KDDL -> KDDLFormatter()
        Format.PLANTUML -> PlantUMLFormatter()
        Format.POSTGRESQL -> PostgreSQLFormatter()
        else -> throw IllegalArgumentException("invalid format")
    }
    val ret = formatter.format(tree)
    println(ret)
}

fun parse(ddl: CharStream): Database {
    val lexer = kddlLexer(ddl)
    val tokenStream = CommonTokenStream(lexer)
    val parser = kddlParser(tokenStream)
    parser.addErrorListener(ConsoleErrorListener())
    // parser.errorHandler = ParsingErrorHandler()
    val root = parser.database()
    return buildAst(root)
}

fun buildAst(astDatabase : kddlParser.DatabaseContext) : Database {
    // database
    val database = Database(astDatabase.name!!.text!!)
    for (astSchema in astDatabase.findSchema()) {
        // schema
        val schema = Schema(database, astSchema.name!!.text!!)
        database.schemas[schema.name] = schema
        for (astTable in astSchema.findTable()) {
            // table
            val table = Table(schema, astTable.name!!.text!!, database.resolveTable(schema, astTable.par), astTable.findDirection()?.text ?: "")
            schema.tables[table.name] = table
            for (astField in astTable.findField()) {
                // field
                val reference = database.resolveTable(schema, astField.reference)
                val pk = astField.pk != null
                val nonNull = astField.optional == null
                val unique = astField.unique != null
                val field = if (reference == null) {
                    // standard field
                    var default: Any? = null
                    val astDefault = astField.findDefault()?.findExpression()
                    if (astDefault != null) {
                        default = when {
                            astDefault.NULL() != null -> null
                            astDefault.STRING() != null -> astDefault.text.removeSurrounding("'")
                            astDefault.findBoolean() != null -> astDefault.text.toBooleanStrict()
                            astDefault.findNumber() != null -> astDefault.text.toDouble()
                            astDefault.findFunction() != null -> astDefault.text /* TODO */
                            else -> throw SemanticException("invalid default value: ${astDefault.text}")
                        }
                    }
                    val type = astField.findType() ?: throw SemanticException("type not found for field: ${astField.text}")
                    Field(table, astField.name!!.text!!, type.text, pk, nonNull, unique, default)
                } else {
                    // link field
                    val fieldName = astField.name!!.text!!
                    val refPk = reference.getOrCreatePrimaryKey()
                    val cascade = astField.CASCADE() != null
                    val direction = astField.findDirection()?.text ?: ""
                    val fieldType = reference.getPrimaryKey().first().type.let { if (it == "serial") "integer" else it }
                    Field(table, fieldName, fieldType, pk, nonNull, unique)
                        .also {
                            val fk = ForeignKey(table, setOf(it), reference, nonNull, unique, cascade, direction)
                            table.foreignKeys.add(fk)
                        }
                }
                table.fields[field.name] = field
            }
        }
        for (astLink in astSchema.findLink()) {
            val left = database.resolveTable(schema, astLink.left) ?: throw SemanticException("left table not found") // should not happen
            val right = database.resolveTable(schema, astLink.right) ?: throw SemanticException("right table not found") // should not happen
            val leftMult = astLink.left_mult != null || astLink.left_single == null
            val rightMult = astLink.right_mult != null || astLink.right_single == null
            if (leftMult && rightMult) {
                val linkTable = Table(left.schema, "${left.name}_${right.name}")
                left.schema.tables[linkTable.name] = linkTable
                arrayOf(left, right).forEach {
                    val pk = it.getOrCreatePrimaryKey()
                    val fkFields = pk.map {
                        val fkField = Field(linkTable, it.name, it.type.let { if (it == "serial") "long" else it}, false, true, false)
                        linkTable.fields[it.name] = fkField
                        fkField
                    }.toSet()
                    val fk = ForeignKey(linkTable, fkFields, it, true, false, true)
                    linkTable.foreignKeys.add(fk)
                }
                schema.tables[linkTable.name] = linkTable
            } else if (leftMult || rightMult) {
                val pkTable = if (leftMult) right else left
                val fkTable = if (leftMult) left else right
                val pk = pkTable.getOrCreatePrimaryKey()
                val fkFields = pk.map {
                    val fkField = fkTable.fields[it.name]
                        ?: Field(fkTable, it.name, it.type.let { if (it == "serial") "long" else it}, false, true, false)
                            .also { fkTable.fields[it.name] = it }
                    if (fkField.type != it.type && it.type == "serial" && fkField.type !in arrayOf("integer", "long"))
                        throw SemanticException("link ${fkTable.name} -> ${pkTable.name}: incompatible fk/pk field types")
                    fkField
                }.toSet()
                val cascade = astLink.CASCADE() != null
                val fk = ForeignKey(fkTable, fkFields, pkTable, true, false, cascade)
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

fun Database.resolveTable(defSchema: Schema?, astTable: kddlParser.QualifiedContext?) : Table? {
    if (astTable == null) return null
    val schema = astTable.ref_schema?.text?.let { schemas[it] } ?: defSchema ?: throw SemanticException("no schema")
    val name = astTable.name!!.text!!
    val table = schema.tables[name] ?: throw SemanticException("table not found: ${schema.name}.$name")
    return table
}

class KDDLFormatter: Formatter {
    override fun format(asm: Database, indent: String) = asm.display(indent).toString()
    override fun format(asm: Schema, indent: String) = asm.display(indent).toString()
    override fun format(asm: Table, indent: String) = asm.display(indent).toString()
    override fun format(asm: Field, indent: String) = asm.display(indent).toString()
    override fun format(asm: ForeignKey, indent: String) = throw NotImplementedError("TODO")
}
