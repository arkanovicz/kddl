package com.republicate.kddl

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import org.antlr.v4.kotlinruntime.CharStream
import org.antlr.v4.kotlinruntime.CharStreams
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual object Utils {

    actual fun getResource(path: String): CharStream = getFile(path)

    actual fun getFile(path: String): CharStream = CharStreams.fromString(readAllText(path), path)

    // see https://www.nequalsonelifestyle.com/2020/11/16/kotlin-native-file-io/
    private fun readAllText(filePath: String): String {
        val returnBuffer = StringBuilder()
        val file = fopen(filePath, "r") ?: throw IllegalArgumentException("Cannot open input file $filePath")

        try {
            memScoped {
                val readBufferLength = 64 * 1024
                val buffer = allocArray<ByteVar>(readBufferLength)
                var line = fgets(buffer, readBufferLength, file)?.toKString()
                while (line != null) {
                    returnBuffer.append(line)
                    line = fgets(buffer, readBufferLength, file)?.toKString()
                }
            }
        } finally {
            fclose(file)
        }

        return returnBuffer.toString()
    }

    actual fun normalizePath(path: String): String {
        // Simple normalization: remove . and .. components
        val parts = path.split("/").filter { it.isNotEmpty() && it != "." }
        val result = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (result.isNotEmpty()) result.removeLast()
            } else {
                result.add(part)
            }
        }
        return (if (path.startsWith("/")) "/" else "") + result.joinToString("/")
    }

    actual fun parentPath(path: String): String? {
        val normalized = normalizePath(path)
        val lastSlash = normalized.lastIndexOf('/')
        return if (lastSlash <= 0) null else normalized.substring(0, lastSlash)
    }
}




