plugins {
    kotlin("jvm")
}

description = "Maven plugin to generate SQL database creation script from kddl model"

dependencies {
    implementation(rootProject)
    compileOnly("org.apache.maven:maven-plugin-api:3.9.16")
    compileOnly("org.apache.maven:maven-core:3.9.16")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.16.0")
}

kotlin {
    jvmToolchain(21)
}

// Expand plugin.xml with project properties
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    val coordinates = mapOf("version" to project.version, "groupId" to project.group, "artifactId" to project.name)
    inputs.properties(coordinates)
    filesMatching("META-INF/maven/plugin.xml") { expand(coordinates) }
}

mavenPublishing {
    pom {
        packaging = "maven-plugin"
    }
}
