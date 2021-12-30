package com.republicate.kddl

class SemanticException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

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
