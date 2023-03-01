package com.republicate.kddl

import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.CharStreams
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.*

actual object Utils {

    actual fun getResource(path: String): CharStream = CharStreams.fromStream(
        {}.javaClass.classLoader.getResourceAsStream(path) ?: throw RuntimeException("resource $path not found"),
        StandardCharsets.UTF_8
    )

    // this version truncates the input at the buffer size... TODO - report the bug upstream
//    actual fun Utils.getFile(path: String): CharStream = CharStreams.fromFileName(
//        path,
//        StandardCharsets.UTF_8)

    actual fun getFile(path: String): CharStream {
        Paths.get(path)
        val content = Scanner(File(path)).useDelimiter("\\A").next();
        return CharStreams.fromString(
            content,
            path
        )
    }
}
