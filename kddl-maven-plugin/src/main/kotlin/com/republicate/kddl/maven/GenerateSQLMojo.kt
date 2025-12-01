package com.republicate.kddl.maven

import com.republicate.kddl.Format
import com.republicate.kddl.KddlProcessor
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File

/**
 * Generates SQL database creation script from a KDDL model file.
 */
@Mojo(
    name = "generate-sql",
    defaultPhase = LifecyclePhase.GENERATE_RESOURCES
)
class GenerateSQLMojo : AbstractMojo() {

    /**
     * Source KDDL model file.
     */
    @Parameter(property = "kddl.model", required = true)
    lateinit var model: File

    /**
     * Output SQL file.
     */
    @Parameter(
        property = "kddl.sql",
        defaultValue = "\${project.build.directory}/generated-resources/kddl/init.sql"
    )
    lateinit var sql: File

    /**
     * Output format: POSTGRESQL, HYPERSQL, PLANTUML, or KDDL.
     */
    @Parameter(property = "kddl.format", defaultValue = "POSTGRESQL")
    var format: String = "POSTGRESQL"

    /**
     * Use quoted identifiers.
     */
    @Parameter(property = "kddl.quoted", defaultValue = "false")
    var quoted: Boolean = false

    /**
     * Use uppercase identifiers.
     */
    @Parameter(property = "kddl.uppercase", defaultValue = "false")
    var uppercase: Boolean = false

    @Throws(MojoExecutionException::class)
    override fun execute() {
        if (!model.exists()) {
            throw MojoExecutionException("Model file not found: ${model.absolutePath}")
        }

        log.info("Generating SQL from KDDL model: ${model.absolutePath}")

        try {
            sql.parentFile?.mkdirs()

            val formatEnum = try {
                Format.valueOf(format.uppercase())
            } catch (e: IllegalArgumentException) {
                throw MojoExecutionException(
                    "Invalid format: $format. Valid: ${Format.entries.joinToString()}"
                )
            }

            val processor = KddlProcessor(
                input = model.absolutePath,
                format = formatEnum,
                driver = null,
                uppercase = uppercase,
                quoted = quoted
            )

            sql.writeText(processor.process())
            log.info("Generated: ${sql.absolutePath}")

        } catch (e: MojoExecutionException) {
            throw e
        } catch (e: Exception) {
            throw MojoExecutionException("Failed to process KDDL model", e)
        }
    }
}
