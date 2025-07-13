@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.dokka)
    signing
    `maven-publish`
    alias(libs.plugins.nexusPublish)
    alias(libs.plugins.versions)
}

description = "KDDL Database model swiss-army knife"

allprojects {
    group = "com.republicate.kddl"
    version = "0.13"

    repositories {
        mavenCentral()
    }

    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    val isRelease = project.hasProperty("release")
    signing {
        isRequired = isRelease
        if (isRelease) {
            useGpgCmd()
            sign(publishing.publications)
        }
    }

    tasks {
        register<Jar>("dokkaJar") {
            from(dokkaHtml)
            dependsOn(dokkaHtml)
            archiveClassifier.set("javadoc")
        }
    }
}

afterEvaluate {
    publishing {
        publications.withType<MavenPublication> {
            pom {
                name.set(project.name)
                description.set(project.description)
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
                    connection.set("scm:git@github.com/arkanovicz/kddl.git")
                    url.set("https://github.com/arkanovicz/kddl")
                }
            }

            artifact(tasks["dokkaJar"])
        }
    }
}


nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            useStaging.set(true)
        }
    }
}

buildscript {
    dependencies {
        classpath("com.strumenta:antlr-kotlin-gradle-plugin:1.0.5")
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
    linuxX64()
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
                languageVersion = "2.0"
                apiVersion = "2.0"
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
            }
        }
    }

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
        antlrClasspath = configurations.detachedConfiguration(
            project.dependencies.create("com.strumenta:antlr-kotlin-target:1.0.5")
        )
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

val signingTasks = tasks.withType<Sign>()

tasks {
    // Tasks depending on code generation
    withType<KotlinCompilationTask<*>> {
        dependsOn(generateKotlinGrammarSource)
    }
    named<DokkaTask>("dokkaHtml") {
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

    // Tasks depending on signing
    withType<AbstractPublishToMaven>().configureEach {
        dependsOn(signingTasks)
    }

    // Other dependencies...
    all {
        if (this.name == "compileTestKotlinNative") this.mustRunAfter("signNativePublication")
        if (this.name == "linkDebugTestNative") this.mustRunAfter("signNativePublication")
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
