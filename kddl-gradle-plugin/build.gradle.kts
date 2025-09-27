plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
    id("org.jetbrains.dokka")
    signing
    alias(libs.plugins.pluginPublish)
}

description = "Gradle plugin to generate SQL database creation script from kddl model"

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.strumenta:antlr-kotlin-gradle-plugin:1.0.3")
    }
}

dependencies {
    implementation(gradleApi())
    api(rootProject)
    testImplementation(gradleTestKit())
}

tasks {
    register<Jar>("sourcesJar") {
        archiveClassifier.set("sources")
        dependsOn("classes")
        from(sourceSets["main"].allSource)
    }
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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("kddl-gradle-plugin") {
                from(components["java"])
                pom {
                    name.set("kddl-gradle-plugin")
                    description.set("kddl-gradle-plugin $version - Gradle plugin to generates SQL database creation scripts from KDDL model file")
                    url.set("https://github.com/arkanovicz/kddl")
                    licenses {
                        license {
                            name.set("The Apache Software License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            name.set("Claude Brisson")
                            email.set("claude.brisson@gmail.com")
                            organization.set("republicate.com")
                            organizationUrl.set("https://republicate.com")
                        }
                    }
                    scm {
                        connection.set("scm:git@github.com:arkanovicz/kddl.git")
                        url.set("https://github.com/arkanovicz/kddl")
                    }
                }
                artifact(tasks["dokkaJar"])
                artifact(tasks["sourcesJar"])
            }
        }
    }
}

tasks {
    withType<GenerateModuleMetadata> {
        dependsOn(named("dokkaJar"))
    }
}
