package com.republicate.kddl

import com.republicate.kddl.postgresql.PostgreSQLReverseFilter
import java.sql.DatabaseMetaData

interface ReverseFilter {
    companion object {
        fun getReverseFilter(metadata: DatabaseMetaData) : ReverseFilter {
            val match = Regex("^jdbc:([^:]+):").find(metadata.url) ?: throw RuntimeException("could not extract tag from jdbc url")
            return when (match.groups[1]?.value) {
                "postgresql" -> PostgreSQLReverseFilter(metadata)
                else -> object: ReverseFilter {}
            }
        }
    }
    fun filterType(name: String, type: String, default: String?): Pair<String, String?> = Pair(type, default)
}

