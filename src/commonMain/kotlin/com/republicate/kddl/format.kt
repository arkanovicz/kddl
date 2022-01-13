package com.republicate.kddl

class SemanticException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

interface Formatter {
    companion object {
        val EOL: String = "\n"
    }
    fun format(asm: ASTDatabase, indent: String = ""): String
    fun format(asm: ASTSchema, indent: String): String
    fun format(asm: ASTTable, indent: String): String
    fun format(asm: ASTField, indent: String, ): String
    fun format(asm: ASTForeignKey, indent: String): String
}
