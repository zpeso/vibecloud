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

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "vibecloud-core",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

dependencies {
    api(project(":api"))
    implementation(project(":common"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.yaml:snakeyaml:2.7")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
