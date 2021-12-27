package com.republicate.kddl

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.MalformedURLException
import java.net.URL
import java.net.URLClassLoader
import java.sql.*
import java.util.*

// runtime loading of jdbc driver
// see https://stackoverflow.com/a/27187663/710286
@Synchronized
@Throws(SQLException::class)
actual fun loadLibrary(jar: String) {
    try {
        /*We are using reflection here to circumvent encapsulation; addURL is not public*/
        val jarFile = File(jar)
        val loader = ClassLoader.getSystemClassLoader() as URLClassLoader
        val url = jarFile.toURI().toURL()
        /*Disallow if already loaded*/for (it in Arrays.asList(*loader.urLs)) {
            if (it == url) {
                return
            }
        }
        val method =
            URLClassLoader::class.java.getDeclaredMethod("addURL", *arrayOf<Class<*>>(URL::class.java))
        method.isAccessible = true /* promote the method to public access */
        method.invoke(loader, *arrayOf<Any>(url))
    } catch (e: NoSuchMethodException) {
        throw SQLException(e)
    } catch (e: IllegalAccessException) {
        throw SQLException(e)
    } catch (e: MalformedURLException) {
        throw SQLException(e)
    } catch (e: InvocationTargetException) {
        throw SQLException(e)
    }
}

fun guessDatabaseName(url: String): String {
    var qm = url.indexOf('?')
    if (qm == -1) qm = url.length
    var slash = Math.max(url.lastIndexOf('/', qm), url.lastIndexOf('\\', qm))
    if (slash == -1) slash = url.lastIndexOf(':', qm)
    if (slash == -1) return "unknown"
    val match = Regex("\\w+").find(url.substring(slash + 1, qm))
    return when(match) {
        null -> "unknown"
        else -> match.value
    }
}

class ResultSetIterator(val rs: ResultSet): Iterator<ResultSet> {
    var prefetched = false
    override fun hasNext(): Boolean {
        return prefetched || rs.next().also {
            prefetched = true
        }
    }
    override fun next(): ResultSet {
        return rs.also {
            if (!prefetched) rs.next()
            prefetched = false
        }
    }
}

fun ResultSet.asSequence() = ResultSetIterator(this).asSequence()

@Throws(SQLException::class)
actual fun reverse(url: String): Database = ReverseEngineer(url).process()

class ReverseEngineer(val url: String) {

    private val connection = connect(url)
    private val metadata = connection.metaData
    private val catalog = connection.catalog ?: guessDatabaseName(url)
    private val vendorFilter = ReverseFilter.getReverseFilter(metadata)

    fun process(): Database {
        return Database(catalog).also {
            reverseDatabase(it)
        }
    }

    fun reverseDatabase(database: Database) {
        schemas {
            val schema = Schema(database, it.getString("TABLE_SCHEM"))
            database.schemas[schema.name] = schema
            reverseSchema(schema)
        }
        reverseForeignKeys(database)
    }

    fun reverseSchema(schema: Schema) {
        tables(schema.name) {
            val table = Table(schema, it.getString("TABLE_NAME"))
            schema.tables[table.name] = table
            reverseTable(table)
        }
    }

    fun reverseTable(table: Table) {

        val keys = mutableSetOf<String>()
        primaryKeys(table.schema.name, table.name) {
            keys.add(it.getString("COLUMN_NAME"))
        }

        val uniqueIndices = mutableMapOf<String, String>()
        uniqueIndices(table.schema.name, table.name) {
            val indexName = it.getString("INDEX_NAME")
            val colName = it.getString("COLUMN_NAME")
            if (uniqueIndices.contains(indexName)) uniqueIndices.remove(indexName)
            else uniqueIndices[indexName] = colName;
        }
        val uniqueCols = uniqueIndices.values.toSet()

        fields(table.schema.name, table.name) {
            var size: Int? = it.getInt("COLUMN_SIZE")
            if (it.wasNull()) size = null // instead of 0
            val fieldName: String = it.getString("COLUMN_NAME")
            val sqlType = it.getInt("DATA_TYPE")
            var dataType = typesMap[sqlType] ?: throw SQLException("unhandled sql type: ${sqlType} (${it.getString("TYPE_NAME")})")
            val colSize = it.getInt("COLUMN_SIZE")
            val colPrec = it.getInt("DECIMAL_DIGITS")
            var columnDef = it.getString("COLUMN_DEF")
            when (dataType) {
                "varchar" -> if (colSize != 0) dataType += "($colSize)" // else dataType += "()"
                "numeric" -> {
                    if (colSize != 0 && colPrec != 0) dataType += "($colSize,$colPrec)"
                    else if (colSize != 0) dataType += "($colSize)"
                    // else warning TODO
                }
            }
            with(vendorFilter.filterType(fieldName, dataType, columnDef)) {
                dataType = first
                columnDef = second
            }
            val nonNull = it.getString("IS_NULLABLE") == "NO"
            val generated = ("YES" == it.getString("IS_AUTOINCREMENT") || "YES" == it.getString("IS_GENERATEDCOLUMN"))
            val field = Field(table, fieldName, dataType, keys.contains(fieldName), nonNull, uniqueCols.contains(fieldName), columnDef)
            table.fields[fieldName] = field
        }
    }

    fun reverseForeignKeys(database: Database) {
        database.schemas.values.flatMap {
                it ->  it.tables.values
        }.forEach { table ->
            val fks = mutableMapOf<String, Triple<Table, MutableList<Field>, Boolean>>()
            foreignKeys(table.schema.name, table.name) {
                val pkSchema = it.getString("PKTABLE_SCHEM")
                val pkTable = it.getString("PKTABLE_NAME")
                val targetTable = database.schemas[pkSchema]?.tables?.get(pkTable) ?: throw SQLException("could not find table ${pkSchema}.${pkTable}")
                val fkName = it.getString("FK_NAME") ?: it.getString("PK_NAME") ?: targetTable.name
                val fieldName = it.getString("FKCOLUMN_NAME")
                val fkField = table.fields[fieldName] ?: throw SQLException("could not find field ${table.name}.$fieldName")
                if (fks.containsKey(fkName)) fks[fkName]!!.second.add(fkField)
                else {
                    // only read cascade info on the first field
                    val cascade = it.getInt("DELETE_RULE") == DatabaseMetaData.importedKeyCascade
                    fks[fkName] = Triple(targetTable, mutableListOf(fkField), cascade)
                }
            }
            fks.values.forEach {
                val nonNull = it.second.all { it.nonNull }
                val unique = nonNull && it.second.any { it.unique }
                val foreignKey = ForeignKey(table, it.second.toSet(), it.first, nonNull, unique, it.third)
                table.foreignKeys.add(foreignKey)
            }
        }
    }

    private fun connect(url: String): Connection {
        DriverManager.getDrivers()
        return DriverManager.getConnection(url)
    }

    private fun schemas(op: (ResultSet) -> Unit) = with(metadata.schemas) {
        asSequence().filter {
            it.getString("TABLE_SCHEM") !in arrayOf("information_schema", "pg_catalog")
        }.forEach(op)
        close()
    }

    private fun tables(schema: String, op: (ResultSet) -> Unit) {
        with (metadata.getTables(catalog, schema, null, arrayOf("TABLE", "VIEW"))) {
            asSequence().filter {
                !arrayOf("SYSTEM TABLE", "SYSTEM VIEW").contains(it.getString("TABLE_TYPE"))
            }.forEach(op)
            close()
        }
    }

    private fun fields(schema: String, table: String, op: (ResultSet) -> Unit) {
        with (metadata.getColumns(catalog, schema, table, null)) {
            asSequence().forEach(op)
            close()
        }
    }

    private fun primaryKeys(schema: String, table: String, op: (ResultSet) -> Unit) {
        with (metadata.getPrimaryKeys(catalog, schema, table)) {
            asSequence().forEach(op)
            close()
        }
    }

    private fun uniqueIndices(schema: String, table: String, op: (ResultSet) -> Unit) {
        with (metadata.getIndexInfo(catalog, schema, table, false, false)) {
            asSequence().filter {
                !it.getBoolean("NON_UNIQUE")
            }.forEach(op)
            close()
        }
    }

    private fun foreignKeys(schema: String, table: String, op: (ResultSet) -> Unit) {
        with (metadata.getImportedKeys(catalog, schema, table)) {
            asSequence().forEach(op)
            close()
        }
    }

    private val typesMap = mapOf<Int, String>(
        Types.BIT to "boolean",
        Types.BLOB to "blob",
        Types.BOOLEAN to "boolean",
        Types.CHAR to "char",
        Types.CLOB to "clob",
        Types.DATE to "date",
        Types.DOUBLE to "double",
        Types.FLOAT to "float",
        Types.INTEGER to "integer",
        Types.NUMERIC to "numeric",
        Types.REAL to "double",
        Types.SMALLINT to "short",
        Types.TIME to "time",
        Types.TIMESTAMP to "datetime",
        Types.VARCHAR to "varchar"
    )
}
