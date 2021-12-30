package com.republicate.kddl

import com.republicate.kddl.parser.kddlLexer
import com.republicate.kddl.parser.kddlParser
import org.antlr.v4.kotlinruntime.ANTLRErrorListener
import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.ConsoleErrorListener

fun parse(ddl: CharStream, errorListener: ANTLRErrorListener = ConsoleErrorListener()): Database {
    val lexer = kddlLexer(ddl)
    val tokenStream = CommonTokenStream(lexer)
    val parser = kddlParser(tokenStream)
    parser.addErrorListener(errorListener)
    val root = parser.database()
    return buildAst(root)
}

