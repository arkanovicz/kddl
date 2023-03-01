package com.republicate.kddl.gradle

import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty

abstract class KotlinPluginExtension(project: Project) {
    val model: RegularFileProperty = project.objects.fileProperty()
    val sql: RegularFileProperty = project.objects.fileProperty().convention(project.layout.buildDirectory.file("generated-resources/main/init.sql"))
}
