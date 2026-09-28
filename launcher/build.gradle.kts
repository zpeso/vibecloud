plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":common"))
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jline:jline:3.29.0")
}

application {
    applicationName = "vibecloud"
    mainClass.set("dev.vibecloud.launcher.MainKt")
}
