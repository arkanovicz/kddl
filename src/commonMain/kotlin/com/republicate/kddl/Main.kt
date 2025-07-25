package com.republicate.kddl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.republicate.kddl.hypersql.HyperSQLFormatter
import com.republicate.kddl.plantuml.PlantUMLFormatter
import com.republicate.kddl.postgresql.PostgreSQLFormatter

enum class Format {
    KDDL,
    PLANTUML,
    POSTGRESQL,
    HYPERSQL
}

fun main(args: Array<String>) = Kddl().main(args)

class KddlProcessor(
    val input: String,
    val format: Format,
    val driver: String? = null,
    val uppercase: Boolean = false,
    val quoted: Boolean = false) {

    fun process(): String {
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
            Format.POSTGRESQL -> PostgreSQLFormatter(quoted=quoted, uppercase=uppercase)
            Format.HYPERSQL -> HyperSQLFormatter(quoted=quoted, uppercase=uppercase)
        }
        return formatter.format(tree)
    }
}

class Kddl: CliktCommand() {

    val input by option("-i", "--input", help="input file or url, required").required()
    val format by option("-f", "--format", help="output format, required").enum<Format>().required()
    val driver by option("-d", "--driver", help="jdbc driver")
    val uppercase by option("-u", "--uppercase", help="uppercase identifiers").flag()
    val quoted by option("-q", "--quoted", help="quoted identifiers").flag()

    override fun run() {
        val processor = KddlProcessor(input, format, driver, uppercase, quoted)
        val ret = processor.process()
        println(ret)
    }
}
