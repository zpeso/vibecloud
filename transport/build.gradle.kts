plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

// Shared by the controller (Java 25) and the agent (Java 21): target the lowest of both so the
// module loads in every Minecraft server JVM.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    // Jedis is synchronous and small; the agent shades it into VibeCloud-Agent.jar.
    implementation("redis.clients:jedis:5.2.0")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
