package com.republicate.kddl

import com.republicate.kddl.Utils.getFile
import com.republicate.kddl.kotlin.KotlinFormatter
import com.republicate.kddl.parser.kddlLexer
import com.republicate.kddl.parser.kddlParser
import com.republicate.kddl.plantuml.PlantUMLFormatter
import com.republicate.kddl.postgresql.PostgreSQLFormatter
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required
import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.CommonTokenStream

val argParser = ArgParser("kddl")

enum class Format {
    PLANTUML,
    POSTGRESQL,
    KOTLIN
}

interface Formatter {
    fun format()
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
        Format.KOTLIN -> KotlinFormatter()
        else -> throw IllegalArgumentException("invalid format")
    }
    formatter.format()
}

fun parse(ddl: CharStream): Database {
    val lexer = kddlLexer(ddl)
    val tokenStream = CommonTokenStream(lexer)
    val parser = kddlParser(tokenStream)
    return Database("TODO")
}