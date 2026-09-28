plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

dependencies {
    api(project(":api"))
    implementation("org.yaml:snakeyaml:2.7")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
