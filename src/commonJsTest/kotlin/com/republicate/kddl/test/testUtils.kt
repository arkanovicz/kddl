package com.republicate.kddl.test

import kotlinx.coroutines.CoroutineScope

actual suspend fun getTestResource(path: String): String {
    TODO("Not yet implemented")
}

actual fun runTest(body: suspend CoroutineScope.() -> Unit) {
}
