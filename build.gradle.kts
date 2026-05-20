plugins {
    kotlin("jvm") version "2.2.0"
    `java-library`
    id("com.vanniktech.maven.publish") version "0.29.0"
}

group = "io.github.jrmarcum"
version = "0.1.2"

repositories {
    mavenCentral()
}

// Chicory — pure-Java WASM runtime, no native code required
val chicoryVersion = "1.0.0"

dependencies {
    implementation("com.dylibso.chicory:runtime:$chicoryVersion")
    implementation("com.dylibso.chicory:wasm:$chicoryVersion")

    // Kotlin coroutines for wasmImport (async URL loading) and InstancePool
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// JDK 25 toolchain; Kotlin 2.2.0 max bytecode target is JVM_24.
// Requires JDK 24+ to run.
kotlin {
    jvmToolchain(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "24"
    targetCompatibility = "24"
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("release") {
    group = "publishing"
    description = "Tags HEAD as v<version> and pushes the tag to origin to trigger CI publish."
    doLast {
        val tag = "v$version"
        for (cmd in listOf(
            listOf("git", "tag", "-f", tag),
            listOf("git", "push", "origin", "-f", "refs/tags/$tag")
        )) {
            val exit = ProcessBuilder(cmd).inheritIO().start().waitFor()
            check(exit == 0) { "'${cmd.joinToString(" ")}' failed (exit $exit)" }
        }
        println("Pushed $tag — CI publish workflow triggered.")
    }
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("io.github.jrmarcum", "universal-wasm-loader-jvm", version.toString())

    pom {
        name.set("Universal WASM Loader JVM")
        description.set("A lightweight, zero-dependency WebAssembly loader for JVM languages.")
        url.set("https://github.com/jrmarcum/universalWasmLoader-jvm")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("jrmarcum")
                name.set("Jon Marcum")
                email.set("jrmarcum.se@gmail.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/jrmarcum/universalWasmLoader-jvm.git")
            developerConnection.set("scm:git:ssh://github.com/jrmarcum/universalWasmLoader-jvm.git")
            url.set("https://github.com/jrmarcum/universalWasmLoader-jvm")
        }
    }
}
