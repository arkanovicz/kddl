package com.republicate.kddl.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual suspend fun getTestResource(path: String): String {
    val file = fopen(path, "r") ?: error("Resource $path not found (fopen returned null)")

    try {
        // Move to end to get file length
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        rewind(file)

        // Allocate a buffer and read the content
        val buffer = nativeHeap.allocArray<ByteVar>(size + 1)
        fread(buffer, 1.convert(), size.convert(), file)
        buffer[size] = 0 // null-terminate

        return buffer.toKString()
    } finally {
        fclose(file)
    }
}

actual fun runTest(body: suspend CoroutineScope.() -> Unit) { runBlocking { body() } }
