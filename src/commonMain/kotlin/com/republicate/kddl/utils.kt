package com.republicate.kddl

import org.antlr.v4.kotlinruntime.CharStream

expect object Utils {
    fun getResource(path: String): CharStream
    fun getFile(path: String): CharStream
}

// TODO use root locale
fun String.withCapital() = replaceFirstChar { it.uppercase() }
fun String.withoutCapital() = replaceFirstChar { it.lowercase() }
