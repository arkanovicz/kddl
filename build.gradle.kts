plugins {
    kotlin("multiplatform") version "1.6.10"
    id("org.jetbrains.dokka") version "1.5.0"
    application
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    signing
}

group = "com.republicate.kddl"
version = "0.1"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

buildscript {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.strumenta.antlr-kotlin:antlr-kotlin-gradle-plugin:6304d5c1c4")
    }
}

kotlin {
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
            kotlinOptions.jvmTarget = "1.8"
        }
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    sourceSets {
        val commonAntlr by creating {
            dependencies {
                api("com.strumenta.antlr-kotlin:antlr-kotlin-runtime:6304d5c1c4")
            }
            kotlin.srcDir("build/generated-src/commonMain/kotlin")
        }
        val commonMain by getting {
            dependsOn(commonAntlr)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.4")
            }
            kotlin.srcDirs += File("build/generated-src/commonMain/kotlin")
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
                runtimeOnly("org.postgresql:postgresql:42.3.1")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.register<com.strumenta.antlrkotlin.gradleplugin.AntlrKotlinTask>("generateKotlinCommonGrammarSource") {
    antlrClasspath = configurations.detachedConfiguration(
            project.dependencies.create("com.strumenta.antlr-kotlin:antlr-kotlin-target:6304d5c1c4")
    )
    // maxHeapSize = "64m"
    packageName = "com.republicate.kddl.parser"
    arguments = listOf("-no-visitor", "-no-listener")
    source = project.objects
            .sourceDirectorySet("antlr", "antlr")
            .srcDir("src/commonMain/antlr").apply {
                include("*.g4")
            }
    outputDirectory = File("build/generated-src/commonMain/kotlin")
    group = "code generation"
}

tasks.filter { it.name.startsWith("compileKotlin") }.forEach { it.dependsOn("generateKotlinCommonGrammarSource") }

application {
    mainClass.set("com.republicate.kddl.MainKt")
}

tasks {
    register<Jar>("dokkaJar") {
        from(dokkaHtml)
        dependsOn(dokkaHtml)
        archiveClassifier.set("javadoc")
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications)
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

nexusPublishing {
    repositories {
        sonatype {
            useStaging.set(true)
        }
    }
}
