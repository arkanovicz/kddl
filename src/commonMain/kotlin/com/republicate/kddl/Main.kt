package com.republicate.kddl

import com.republicate.kddl.Utils.getFile
import com.republicate.kddl.plantuml.PlantUMLFormatter
import com.republicate.kddl.postgresql.PostgreSQLFormatter
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required

val argParser = ArgParser("kddl")

enum class Format {
    KDDL,
    PLANTUML,
    POSTGRESQL
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

