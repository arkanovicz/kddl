package com.republicate.kddl

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.required

val argParser = ArgParser("kddl")

enum class Format {
    PLANTUML,
    POSTGRESQL,
    MODALITY
}

fun main(args: Array<String>) {
    val input by argParser.option(ArgType.String, shortName = "i", description = "input file").required()
    val format by argParser.option(ArgType.Choice<Format>(), shortName = "f", description = "output format").required()
    argParser.parse(args)
    val model = getFile(input)
    println("Hello.")
}
