package com.republicate.kddl.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise

actual suspend fun getTestResource(path: String): String {
    TODO("Not yet implemented")
}
@OptIn(DelicateCoroutinesApi::class)
actual fun runTest(body: suspend CoroutineScope.() -> Unit) {
    // We would need workers...
}
