package com.republicate.kddl.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

actual suspend fun getTestResource(path: String) = {}.javaClass.classLoader.getResource(path)?.readText() ?: throw RuntimeException("resource $path not found")
actual fun runTest(body: suspend CoroutineScope.() -> Unit) { runBlocking { body() } }
