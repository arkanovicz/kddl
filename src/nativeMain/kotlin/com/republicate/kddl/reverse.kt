package com.republicate.kddl

actual fun loadLibrary(jar: String) {
    throw UnsupportedOperationException("no native JDBC")
}

actual fun reverse(url: String): Database {
    throw UnsupportedOperationException("no native JDBC")
}

