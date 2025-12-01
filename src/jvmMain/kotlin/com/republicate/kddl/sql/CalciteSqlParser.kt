package com.republicate.kddl.sql

import com.republicate.kddl.*
import org.apache.calcite.sql.*
import org.apache.calcite.sql.ddl.SqlColumnDeclaration
import org.apache.calcite.sql.ddl.SqlCreateTable
import org.apache.calcite.sql.ddl.SqlDropTable
import org.apache.calcite.sql.ddl.SqlKeyConstraint
import org.apache.calcite.sql.parser.SqlParseException
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.parser.ddl.SqlDdlParserImpl

/**
 * SQL DDL parser using Apache Calcite.
 * Converts SQL CREATE TABLE, CREATE TYPE, etc. to KDDL AST.
 */
class CalciteSqlParser(
    private val dialect: SqlDialect = SqlDialect.POSTGRESQL
) {
    enum class SqlDialect {
        POSTGRESQL, MYSQL
    }

    private val parserConfig = SqlParser.config()
        .withParserFactory(SqlDdlParserImpl.FACTORY)
        .withCaseSensitive(false)

    // Track custom types (PostgreSQL CREATE TYPE ... AS ENUM)
    private val customTypes = mutableMapOf<String, List<String>>()

    /**
     * Parse SQL DDL script and return KDDL AST.
     */
    fun parse(sql: String, databaseName: String = "database"): ASTDatabase {
        val db = ASTDatabase(databaseName)
        val defaultSchema = ASTSchema(db, "public")
        db.schemas[defaultSchema.name] = defaultSchema

        // Split into statements and parse each
        val statements = splitStatements(sql)

        // First pass: collect custom types
        statements.forEach { stmt ->
            tryParseCreateType(stmt)
        }

        // Second pass: process tables
        statements.forEach { stmt ->
            tryParseStatement(stmt, db, defaultSchema)
        }

        return db
    }

    private fun splitStatements(sql: String): List<String> {
        // Simple split by semicolon, respecting string literals
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var stringChar = ' '

        for (char in sql) {
            when {
                !inString && (char == '\'' || char == '"') -> {
                    inString = true
                    stringChar = char
                    current.append(char)
                }
                inString && char == stringChar -> {
                    inString = false
                    current.append(char)
                }
                !inString && char == ';' -> {
                    val stmt = current.toString().trim()
                    if (stmt.isNotEmpty()) statements.add(stmt)
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        val remaining = current.toString().trim()
        if (remaining.isNotEmpty()) statements.add(remaining)

        return statements
    }

    private fun tryParseCreateType(sql: String) {
        // PostgreSQL: CREATE TYPE status AS ENUM ('pending', 'active')
        val enumRegex = Regex(
            """CREATE\s+TYPE\s+(\w+)\s+AS\s+ENUM\s*\(([^)]+)\)""",
            RegexOption.IGNORE_CASE
        )
        enumRegex.find(sql)?.let { match ->
            val typeName = match.groupValues[1]
            val values = match.groupValues[2]
                .split(",")
                .map { it.trim().removeSurrounding("'") }
            customTypes[typeName.lowercase()] = values
        }
    }

    private fun tryParseStatement(sql: String, db: ASTDatabase, schema: ASTSchema) {
        try {
            val parser = SqlParser.create(sql, parserConfig)
            when (val node = parser.parseStmt()) {
                is SqlCreateTable -> processCreateTable(node, schema)
                is SqlDropTable -> { /* skip */ }
                else -> {
                    // Try ALTER TABLE for foreign keys, CREATE INDEX
                    tryParseAlterTable(sql, schema)
                    tryParseCreateIndex(sql, schema)
                }
            }
        } catch (e: SqlParseException) {
            // Skip unparseable statements (comments, grants, etc.)
        }
    }

    private fun processCreateTable(node: SqlCreateTable, schema: ASTSchema) {
        val tableName = node.name.simple.lowercase()
        val table = ASTTable(schema, tableName)
        schema.tables[tableName] = table

        // Process column definitions
        node.columnList?.forEach { col ->
            when (col) {
                is SqlColumnDeclaration -> processColumn(col, table)
                is SqlKeyConstraint -> processTableConstraint(col, table)
            }
        }
    }

    private fun processColumn(col: SqlColumnDeclaration, table: ASTTable) {
        val name = col.name.simple.lowercase()
        val dataType = col.dataType
        val (kddlType, isNullable) = mapDataType(dataType)

        // Check constraints
        var isPrimaryKey = false
        var isUnique = false
        var defaultValue: Any? = null

        col.expression?.let { expr ->
            // Default value
            defaultValue = extractDefaultValue(expr)
        }

        // Check column constraints via string representation
        val typeStr = dataType.toString().uppercase()
        if (typeStr.contains("PRIMARY KEY")) isPrimaryKey = true
        if (typeStr.contains("UNIQUE")) isUnique = true

        val field = ASTField(
            table = table,
            name = name,
            type = kddlType,
            primaryKey = isPrimaryKey,
            nonNull = !isNullable,
            unique = isUnique,
            indexed = false,
            default = defaultValue
        )
        table.fields[name] = field
    }

    private fun mapDataType(dataType: SqlDataTypeSpec): Pair<String, Boolean> {
        val typeName = dataType.typeName.simple.lowercase()
        val nullable = dataType.nullable ?: true

        val kddlType = when (typeName) {
            // Integer types
            "int", "integer", "int4" -> "integer"
            "bigint", "int8" -> "bigint"
            "smallint", "int2" -> "smallint"
            "serial", "serial4" -> "serial"
            "bigserial", "serial8" -> "bigserial"

            // Text types
            "varchar", "character varying" -> {
                val precision = extractPrecision(dataType)
                if (precision != null && precision > 0) "varchar($precision)" else "varchar"
            }
            "char", "character" -> {
                val precision = extractPrecision(dataType)
                if (precision != null && precision > 0) "char($precision)" else "char"
            }
            "text" -> "text"

            // Numeric
            "numeric", "decimal" -> {
                val p = extractPrecision(dataType)
                val s = extractScale(dataType)
                when {
                    p != null && p > 0 && s != null && s > 0 -> "numeric($p,$s)"
                    p != null && p > 0 -> "numeric($p)"
                    else -> "numeric"
                }
            }
            "real", "float4" -> "float"
            "double precision", "float8", "double" -> "double"

            // Boolean
            "boolean", "bool" -> "boolean"

            // Date/Time
            "date" -> "date"
            "time" -> "time"
            "timestamp" -> "timestamp"
            "timestamptz", "timestamp with time zone" -> "timestamptz"

            // Binary
            "bytea" -> "blob"

            // Special
            "uuid" -> "uuid"
            "json", "jsonb" -> "json"

            // Check for custom enum type
            else -> {
                customTypes[typeName]?.let { values ->
                    "enum(${values.joinToString(",") { "'$it'" }})"
                } ?: typeName
            }
        }

        return kddlType to nullable
    }

    private fun extractPrecision(dataType: SqlDataTypeSpec): Int? {
        // Access precision via the type name's operand list if available
        val typeName = dataType.typeName
        if (typeName is SqlBasicTypeNameSpec) {
            return typeName.precision.takeIf { it >= 0 }
        }
        return null
    }

    private fun extractScale(dataType: SqlDataTypeSpec): Int? {
        val typeName = dataType.typeName
        if (typeName is SqlBasicTypeNameSpec) {
            return typeName.scale.takeIf { it >= 0 }
        }
        return null
    }

    private fun extractDefaultValue(expr: SqlNode): Any? {
        return when (expr) {
            is SqlLiteral -> expr.toValue()
            is SqlIdentifier -> expr.simple
            else -> expr.toString()
        }
    }

    private fun processTableConstraint(constraint: SqlKeyConstraint, table: ASTTable) {
        // Get column list via operands
        val operands = constraint.operandList
        if (operands.size < 2) return

        val columnListNode = operands[1]
        if (columnListNode !is SqlNodeList) return

        val columns = columnListNode.list.mapNotNull {
            (it as? SqlIdentifier)?.simple?.lowercase()
        }

        val constraintStr = constraint.toString().uppercase()
        if (constraintStr.contains("PRIMARY KEY")) {
            columns.forEach { colName ->
                table.fields[colName]?.let { field ->
                    // Recreate field with primaryKey = true
                    table.fields[colName] = ASTField(
                        table, field.name, field.type,
                        primaryKey = true,
                        nonNull = true,
                        unique = field.unique,
                        indexed = field.indexed,
                        default = field.default
                    )
                }
            }
        }
    }

    private fun tryParseCreateIndex(sql: String, schema: ASTSchema) {
        // CREATE [UNIQUE] INDEX name ON table (col1, col2)
        val indexRegex = Regex(
            """CREATE\s+(UNIQUE\s+)?INDEX\s+\w+\s+ON\s+(\w+)\s*\(([^)]+)\)""",
            RegexOption.IGNORE_CASE
        )
        indexRegex.find(sql)?.let { match ->
            val isUnique = match.groupValues[1].isNotBlank()
            val tableName = match.groupValues[2].lowercase()
            val columns = match.groupValues[3]
                .split(",")
                .map { it.trim().lowercase() }

            schema.tables[tableName]?.let { table ->
                val fields = columns.mapNotNull { table.fields[it] }.toSet()
                if (fields.isNotEmpty()) {
                    table.getOrCreateIndex(fields, isUnique)
                }
            }
        }
    }

    private fun tryParseAlterTable(sql: String, schema: ASTSchema) {
        // ALTER TABLE x ADD CONSTRAINT fk FOREIGN KEY (col) REFERENCES y(col)
        val fkRegex = Regex(
            """ALTER\s+TABLE\s+(\w+)\s+ADD\s+(?:CONSTRAINT\s+\w+\s+)?FOREIGN\s+KEY\s*\((\w+)\)\s*REFERENCES\s+(\w+)\s*\((\w+)\)""",
            RegexOption.IGNORE_CASE
        )
        fkRegex.find(sql)?.let { match ->
            val tableName = match.groupValues[1].lowercase()
            val columnName = match.groupValues[2].lowercase()
            val refTable = match.groupValues[3].lowercase()
            val refColumn = match.groupValues[4].lowercase()

            schema.tables[tableName]?.let { table ->
                schema.tables[refTable]?.let { targetTable ->
                    table.fields[columnName]?.let { field ->
                        val fk = ASTForeignKey(
                            from = table,
                            fields = setOf(field),
                            towards = targetTable,
                            nonNull = field.nonNull,
                            unique = field.unique,
                            cascade = sql.uppercase().contains("ON DELETE CASCADE"),
                            direction = ""
                        )
                        table.foreignKeys.add(fk)
                    }
                }
            }
        }
    }
}
