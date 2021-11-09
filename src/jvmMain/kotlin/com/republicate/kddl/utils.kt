package com.republicate.kddl

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.CharStreams
import java.nio.charset.StandardCharsets

actual object Utils {

    actual fun Utils.getResource(path: String): CharStream = CharStreams.fromStream(
        {}.javaClass.classLoader.getResourceAsStream(path) ?: throw RuntimeException("resource $path not found"),
        StandardCharsets.UTF_8
    )

    actual fun Utils.getFile(path: String): CharStream = CharStreams.fromFileName(
        path,
        StandardCharsets.UTF_8)

}






