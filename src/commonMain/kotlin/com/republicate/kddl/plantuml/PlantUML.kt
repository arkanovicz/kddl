package com.republicate.kddl.plantuml

import com.republicate.kddl.*
import com.republicate.kddl.Formatter.Companion.EOL

object Creole {
    val BOLD = "**"
    val ITALIC = "//"
}

class PlantUMLFormatter : Formatter {
    override fun format(asm: ASTDatabase, indent: String): String {
        val ret = StringBuilder()
        ret.append("@startuml${EOL}'' Database ${asm.name}$EOL")
        // TODO plantuml options

        // tables
        ret.append(
            asm.schemas.map {
                format(it.value, indent)
            }.joinToString(separator = EOL)
        )

        // links
        asm.schemas
            .flatMap { it.value.tables.values }
            .flatMap { it.foreignKeys }
            .forEach {
                ret.append(format(it, "$indent  "))
            }

        ret.append("$indent hide methods$EOL")
        ret.append("@enduml$EOL")
        return ret.toString()
    }

    override fun format(asm: ASTSchema, indent: String): String {
        val ret = StringBuilder("${indent}package ${asm.name} {$EOL")
        ret.append(
            asm.tables.map {
                format(it.value, "${indent}  ")
            }.joinToString(separator = EOL)
        )
        ret.append("${indent}}$EOL")
        return ret.toString()
    }

    override fun format(asm: ASTTable, indent: String): String {
        val ret = StringBuilder()
        if (asm !is JoinTable) {
            ret.append("${indent}class ${asm.name}")
            val fields = asm.fields.values.filter {
                !(it.isDefaultKey() || it.isLinkField() && it.isImplicitLinkField()) // CB TODO - we're scanning twice the foreign keys
            }
            if (fields.isNotEmpty()) {
                ret.append(" {")

                for (field in fields) {
                    ret.append(EOL)
                    ret.append(format(field, "${indent}  {field} "))
                }
                ret.append("$EOL$indent}")
            }
            ret.append(EOL)
            if (asm.parent != null) with(asm) {
                ret.append("${indent}$name -${asm.parentDirection.removeSurrounding("(", ")")}-|> ${parent!!.name}$EOL")
            }
        }
        return ret.toString()
    }

    override fun format(asm: ASTField, indent: String): String {
        val ret = StringBuilder(indent)
        asm.apply {
            // decorated name
            if (!nonNull) ret.append(Creole.ITALIC)
            else if (primaryKey) ret.append(Creole.BOLD)
            ret.append(name)
            if (primaryKey) ret.append(Creole.BOLD)
            if (type.isNotEmpty()) {
                if (type.startsWith("enum(")) {
                    ret.append(' ').append(
                        type.removePrefix("enum")
                            .removeSurrounding("(", ")")
                            .split(',').joinToString(separator = "|") {
                                it.trim().removeSurrounding("'")
                            }
                    )
                }
                else ret.append(" $type")
            }
            if (!nonNull) ret.append(Creole.ITALIC)
        }
        return ret.toString()
    }

    override fun format(asm: ASTForeignKey, indent: String): String {
        val ret = StringBuilder()
        val line = if (asm.nonNull) "-" else "."
        if (asm.from is JoinTable) {
            if (asm.from.sourceTable == asm.towards) {
                ret.append("${indent}${asm.from.sourceTable.name} }${line}${asm.direction.removeSurrounding("(", ")")}${line}{ ${asm.from.destTable.name}")
                ret.append(EOL)
            } // else NOP
        }
        else {
            ret.append("${indent}${asm.from.name} ")
            if (!asm.unique) ret.append('}')
            ret.append("${line}${asm.direction.removeSurrounding("(", ")")}${line}> ")
            // if (asm.towards.schema != asm.from.schema) ret.append(asm.towards.schema.name).append('.')
            ret.append(asm.towards.name)
            if (asm.isFieldLink()) {
                val linkName = asm.fields.first().name.removeSuffix(suffix)
                if (linkName != asm.towards.name) ret.append(" : $linkName")
            }
            ret.append(EOL)
        }
        return ret.toString()
    }
}
