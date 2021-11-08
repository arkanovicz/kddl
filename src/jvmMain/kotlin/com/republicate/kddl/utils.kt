package com.republicate.kddl

import java.io.File

actual fun getResource(path: String) = {}.javaClass.classLoader.getResource(path)?.readText() ?: throw RuntimeException("resource $path not found")
actual fun getFile(path: String) = File(path).readText(Charsets.UTF_8)



