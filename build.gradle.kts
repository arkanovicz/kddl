plugins {
    kotlin("multiplatform") version "2.0.21"
    id("org.jetbrains.dokka") version "1.9.20"
    application
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    signing
}

tasks {
    register<Jar>("dokkaJar") {
        from(dokkaHtml)
        dependsOn(dokkaHtml)
        archiveClassifier.set("javadoc")
    }
}

group = "com.republicate.kddl"
version = "0.10"

signing {
    useGpgCmd()
    sign(publishing.publications)
}

apply(plugin = "io.github.gradle-nexus.publish-plugin")

nexusPublishing {
    repositories {
        sonatype {
            useStaging.set(true)
        }
    }
}

repositories {
    mavenCentral()
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.strumenta:antlr-kotlin-gradle-plugin:1.0.1")
    }
}

kotlin {
    sourceSets.all {
        languageSettings.apply {
            languageVersion = "1.9"
            apiVersion = "1.9"
        }
        languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }
    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosX64("native")
        hostOs == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }
    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                api("com.strumenta:antlr-kotlin-runtime:1.0.1")
                implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.4")
            }
            kotlin.srcDir("build/generated-src/commonMain/kotlin")
        }
        val commonTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")
            }
        }
        val nativeMain by getting
        val nativeTest by getting
        val jvmMain by getting {
            dependencies {
                runtimeOnly("org.postgresql:postgresql:42.5.4")
                runtimeOnly("mysql:mysql-connector-java:8.0.32")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
    // explicitApi()
}

val generateKotlinGrammarSource = tasks.register<com.strumenta.antlrkotlin.gradle.AntlrKotlinTask>("generateKotlinCommonGrammarSource") {
    // dependsOn("cleanGenerateKotlinGrammarSource")
    antlrClasspath = configurations.detachedConfiguration(
            project.dependencies.create("com.strumenta:antlr-kotlin-target:1.0.1")
    )
    packageName = "com.republicate.kddl.parser"
    // maxHeapSize = "64m"

    arguments = listOf(
      "-Dlanguage=Kotlin",
      "-no-visitor",
      "-no-listener",
      "-encoding", "UTF-8"
    )
    source = project.objects
            .sourceDirectorySet("antlr", "antlr")
            .srcDir("src/commonMain/antlr").apply {
                include("*.g4")
            }
    outputDirectory = File("build/generated-src/commonMain/kotlin")
    group = "code generation"
}

tasks {
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>> {
    dependsOn(generateKotlinGrammarSource)
  }

  //
  // The source JAR tasks must explicitly depend on the grammar generation
  // to avoid Gradle complaining and erroring out
  //
  /* Fails with:
     The task 'jvmSourcesJar' (org.gradle.jvm.tasks.Jar) is not a subclass of the given type (org.gradle.api.tasks.bundling.Jar).
  sourcesJar {
    dependsOn(generateKotlinGrammarSource)
  }

  kotlin.targets.configureEach {
    if (publishable) {
      named<Jar>("${targetName}SourcesJar") {
        dependsOn(generateKotlinGrammarSource)
      }
    }
  }
  */
}

application {
    mainClass.set("com.republicate.kddl.MainKt")
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("kddl")
            description.set("Database model generator")
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
    }
}
