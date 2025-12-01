plugins {
    kotlin("jvm")
    `maven-publish`
    id("org.jetbrains.dokka")
    signing
}

description = "Maven plugin to generate SQL database creation script from kddl model"

dependencies {
    implementation(rootProject)
    compileOnly("org.apache.maven:maven-plugin-api:3.9.9")
    compileOnly("org.apache.maven:maven-core:3.9.9")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.1")
}

kotlin {
    jvmToolchain(21)
}

// Expand plugin.xml with project properties
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesMatching("META-INF/maven/plugin.xml") {
        expand(
            "version" to project.version,
            "groupId" to project.group,
            "artifactId" to project.name
        )
    }
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    dependsOn("classes")
    from(sourceSets["main"].allSource)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("kddl-maven-plugin") {
                from(components["java"])
                artifact(tasks["sourcesJar"])
                pom {
                    name.set("kddl-maven-plugin")
                    description.set("kddl-maven-plugin $version - Maven plugin to generate SQL database creation scripts from KDDL model file")
                    url.set("https://github.com/arkanovicz/kddl")
                    packaging = "maven-plugin"
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
            }
        }
    }
}

tasks {
    withType<GenerateModuleMetadata> {
        dependsOn(named("dokkaJar"))
    }
    withType<AbstractPublishToMaven>().configureEach {
        dependsOn(withType<Sign>())
    }
}
