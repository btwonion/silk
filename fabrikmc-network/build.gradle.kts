import BuildConstants.projectTitle

plugins {
    `java-version-script`
    `mod-build-script`
    `mod-publish-script`
    `dokka-script`
    kotlin("plugin.serialization")
}

dependencies {
    implementation(modProject(":${rootProject.name}-core"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.3.0")
}

val modName by extra("$projectTitle Network")
val modEntrypoints by extra(linkedMapOf(
    "main" to listOf("net.axay.fabrik.network.internal.FabrikNetwork"),
    "client" to listOf("net.axay.fabrik.network.internal.FabrikNetwork"),
))
