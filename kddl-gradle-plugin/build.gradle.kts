plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    alias(libs.plugins.pluginPublish)
}

description = "Gradle plugin to generate SQL database creation script from kddl model"

dependencies {
    implementation(gradleApi())
    api(rootProject)
    testImplementation(gradleTestKit())
}


gradlePlugin {
    website = "https://github.com/arkanovicz/kddl"
    vcsUrl = "https://github.com/arkanovicz/kddl"
    plugins {
        create("KddlPlugin") {
            id = "com.republicate.kddl"
            implementationClass = "com.republicate.kddl.gradle.KddlPlugin"
            version = project.version as String
            displayName = "Gradle plugin to generate SQL database creation script from a Kddl model."
            description = "Kddl is intended to be a Swiss army knife for database models."
            tags.set(listOf("kddl", "database", "sql", "model", "script"))
        }
    }
}
