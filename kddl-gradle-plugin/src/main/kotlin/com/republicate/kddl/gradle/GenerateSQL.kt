package com.republicate.kddl.gradle

import com.republicate.kddl.Utils
import com.republicate.kddl.parse
import com.republicate.kddl.postgresql.PostgreSQLFormatter
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.FileWriter

@CacheableTask
abstract class GenerateSQL: DefaultTask() {
    init {
        description = "Generates SQL database creation script from kddl model"
        group = "code generation"
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Option(option = "model", description = "Source kddl model")
    abstract val model: RegularFileProperty

    @get:OutputFile
    @get:Option(option = "sql", description = "Destination sql file")
    abstract val sql: RegularFileProperty

    @TaskAction
    fun generateSQL() {
        val sqlFile = sql.get().asFile
        sqlFile.parentFile.mkdirs()
        val writer = FileWriter(sqlFile)
        val modelStream = Utils.getFile(model.get().asFile.absolutePath)
        val ast = parse(modelStream)
        writer.write(PostgreSQLFormatter(quoted=false, uppercase=false).format(ast))
        writer.flush();
        writer.close()
    }
}
