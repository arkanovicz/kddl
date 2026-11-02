@file:OptIn(ExperimentalKotlinGradlePluginApi::class, KotlinNativeCacheApi::class)

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
    signing
    alias(libs.plugins.versions)
    alias(libs.plugins.antlrKotlin)
}

description = "KDDL Database model swiss-army knife"

// only the Central upload needs signatures; publishToMavenLocal feeds scratch consumers
val releasing = gradle.startParameter.taskNames.any { it.contains("MavenCentral") }

allprojects {
    group = "com.republicate.kddl"
    version = "0.33"

    repositories {
        mavenCentral()
    }

    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "signing")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        if (releasing) signAllPublications()
        pom {
            name.set(project.name)
            description.set(provider { project.description })
            url.set("https://github.com/arkanovicz/kddl")
            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("cbrisson")
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

    configure<SigningExtension> {
        useGpgCmd()
    }
    // plugin-publish signs its publications on its own whenever signing is applied
    tasks.withType<Sign>().configureEach {
        enabled = releasing
    }
}

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("commonJs") {
                withJs()
            }
        }
    }

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.get().compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    js {
        nodejs()
        // compilations.all { compileKotlinTask.kotlinOptions.freeCompilerArgs += listOf("-Xir-minimized-member-names=false") }
    }
    linuxX64 {
        binaries.all {
            disableNativeCache(DisableCacheInKotlinVersion.`2_3_21`, "duplicate clikt.core symbol at link time")
        }
    }
    linuxArm64()
    mingwX64()
    macosX64()
    macosArm64()

    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

    sourceSets {
        all {
            languageSettings.apply {
                languageVersion = "2.1"
                apiVersion = "2.1"
            }
        }

        commonMain {
            dependencies {
                api(libs.antlr.kotlin.runtime)
                implementation(libs.clikt)
            }
            kotlin.srcDir("build/generated-src/commonMain/kotlin")
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.core)
            }
        }
        nativeMain
        nativeTest
        jvmMain {
            dependencies {
                runtimeOnly(libs.postgresql)
                runtimeOnly(libs.mysql.connector)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.testcontainers.postgresql)
                implementation(libs.testcontainers.mysql)
                implementation(libs.h2)
                implementation(libs.hsqldb)
            }
        }
    }

    // ReadmeTest compares the README example block to the model it quotes
    tasks.named<Test>("jvmTest") { inputs.file("README.md") }

    val nativeTestResourcesPath = "${layout.buildDirectory.get()}/processedResources/native/test"
    val copyNativeTestResources = project.tasks.register<Copy>("copyNativeTestResources") {
        from("src/commonTest/resources")
        into(nativeTestResourcesPath)
    }
    tasks.withType<KotlinNativeTest>().configureEach {
        dependsOn(copyNativeTestResources)
        workingDir = nativeTestResourcesPath
    }
}

val generateKotlinGrammarSource =
    tasks.register<com.strumenta.antlrkotlin.gradle.AntlrKotlinTask>("generateKotlinCommonGrammarSource") {
        // dependsOn("cleanGenerateKotlinGrammarSource")
        antlrClasspath = configurations.detachedConfiguration(libs.antlr.kotlin.target.get())
        packageName = "com.republicate.kddl.parser"

        arguments = listOf(
            "-Dlanguage=Kotlin", "-no-visitor", "-no-listener", "-encoding", "UTF-8"
        )
        source = project.objects.sourceDirectorySet("antlr", "antlr").srcDir("src/commonMain/antlr").apply {
                include("*.g4")
            }
        outputDirectory = File("build/generated-src/commonMain/kotlin")
        group = "code generation"
    }

tasks {
    // Tasks depending on code generation
    withType<KotlinCompilationTask<*>> {
        dependsOn(generateKotlinGrammarSource)
    }
    withType<DokkaGenerateTask>().configureEach {
        dependsOn(generateKotlinGrammarSource)
    }
    sourcesJar {
        dependsOn(generateKotlinGrammarSource)
    }
    kotlin.targets.configureEach {
        if (publishable) {
            named<org.gradle.jvm.tasks.Jar>("${targetName}SourcesJar") {
                dependsOn(generateKotlinGrammarSource)
            }
        }
    }

    // Set main class in jvm jar
    named<Jar>("jvmJar") {
        manifest { attributes["Main-Class"] = "com.republicate.kddl.MainKt" }
    }
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the JVM application"
    val jvmJar = tasks.named<Jar>("jvmJar")
    dependsOn(jvmJar)
    val jarFile = jvmJar.get().archiveFile.get().asFile
    classpath = files(jarFile) + configurations.getByName("jvmRuntimeClasspath")
    mainClass.set("com.republicate.kddl.MainKt")
    if (project.hasProperty("args")) {
        args = (project.property("args") as String).split("\\s+".toRegex())
    }
}
