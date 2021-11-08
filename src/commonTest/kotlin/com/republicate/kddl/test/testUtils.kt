package com.republicate.kddl.test

import kotlinx.coroutines.CoroutineScope

expect suspend fun getTestResource(path: String): String
expect fun runTest(body: suspend CoroutineScope.() -> Unit)
