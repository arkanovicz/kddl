package com.republicate.kddl

import com.republicate.kddl.Utils.getFile
import com.republicate.kddl.parser.kddlLexer
import com.republicate.kddl.parser.kddlParser
import com.republicate.kddl.plantuml.PlantUMLFormatter
import com.republicate.kddl.postgresql.PostgreSQLFormatter
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.Parser
import org.antlr.v4.kotlinruntime.tree.Tree
import org.antlr.v4.kotlinruntime.tree.Trees

class SemanticException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

val argParser = ArgParser("kddl")

enum class Format {
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
    val input by argParser.option(ArgType.String, shortName = "i", description = "input file").required()
    val format by argParser.option(ArgType.Choice<Format>(), shortName = "f", description = "output format").required()
    argParser.parse(args)

    val ddl = Utils.getFile(input)
    val tree = parse(ddl)
    val formatter = when (format) {
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
                    val cascade = astField.CASCADE() != null
                    val direction = astField.findDirection()?.text ?: ""
                    Field(table, astField.name!!.text!!, reference!!.getPrimaryKey().first().type /* TODO */, pk, nonNull, unique)
                        .also {
                            val fk = ForeignKey(table, setOf(it), reference, nonNull, unique, cascade, direction)
                            table.foreignKeys.add(fk)
                        }
                }
                table.fields[field.name] = field
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

fun Database.resolveTable(schema: Schema, astTable: kddlParser.QualifiedContext?) : Table? {
    if (astTable == null) return null
    val ref = astTable.ref_schema?.text
    val name = astTable.name!!.text!!
    val table = when(ref) {
        null -> schema.tables[astTable.name!!.text!!] ?: throw SemanticException("table not found: ${schema.name}.$name")
        else -> schemas[ref]?.tables?.get(astTable.name!!.text!!) ?: throw SemanticException("table not found: $ref.$name")
    }
    return table
}
