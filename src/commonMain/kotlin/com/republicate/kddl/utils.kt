package com.republicate.kddl

import org.antlr.v4.kotlinruntime.CharStream

expect object Utils {
    fun Utils.getResource(path: String): CharStream
    fun Utils.getFile(path: String): CharStream
}


