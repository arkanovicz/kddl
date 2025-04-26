plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
    id("org.jetbrains.dokka")
    signing
}

 repositories {
     mavenCentral()
     mavenLocal() // for now, to get latest kddl version
     maven("https://jitpack.io") // for antlr-kotlin
 }

buildscript {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.strumenta:antlr-kotlin-gradle-plugin:1.0.1")
    }
}

group = "com.republicate.kddl"
version = "0.10"

dependencies {
    implementation(gradleApi())
    api(rootProject)
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.12")
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

tasks {
    withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
        kotlinOptions {
            languageVersion = "1.7"
            apiVersion = "1.7"
        }
    }
    withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_11.toString()
        targetCompatibility = JavaVersion.VERSION_11.toString()
    }
}

tasks {
    register<Jar>("dokkaJar") {
        from(dokkaHtml)
        dependsOn(dokkaHtml)
        archiveClassifier.set("javadoc")
    }

    register<Jar>("sourcesJar") {
        archiveClassifier.set("sources")
        dependsOn("classes")
        from(sourceSets["main"].allSource)
    }
}


gradlePlugin {
    plugins {
        create("KddlPlugin") {
            id = "kddl-gradle-plugin"
            implementationClass = "com.republicate.kddl.KddlGradlePlugin"
            version = "0.10"
        }
    }
    isAutomatedPublishing = false
}

afterEvaluate {
    tasks.withType<GenerateMavenPom>() {
        doFirst {
            with(pom) {
                group = "com.republicate.kddl"
                name.set("kddl-gradle-plugin")
                // packaging = "jar"
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
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications)
}

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
