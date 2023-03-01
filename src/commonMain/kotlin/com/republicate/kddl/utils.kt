package com.republicate.kddl

import org.antlr.v4.kotlinruntime.CharStream

expect object Utils {
    fun getResource(path: String): CharStream
    fun getFile(path: String): CharStream
}


