package com.republicate.kddl.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class KddlPlugin: Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("kddl", KotlinPluginExtension::class.java, project)
        project.tasks.register("generateSQL", GenerateSQL::class.java) {
            it.model.set(extension.model)
            it.sql.set(extension.sql)
        }
    }
}
