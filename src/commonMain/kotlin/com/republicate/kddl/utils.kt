package com.republicate.kddl

import org.antlr.v4.kotlinruntime.CharStream

expect object Utils {
    fun getResource(path: String): CharStream
    fun getFile(path: String): CharStream
    fun normalizePath(path: String): String
    fun parentPath(path: String): String?
}

// TODO use root locale
fun String.withCapital() = replaceFirstChar { it.uppercase() }
fun String.withoutCapital() = replaceFirstChar { it.lowercase() }
