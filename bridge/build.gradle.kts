plugins {
    kotlin("jvm")
    `maven-publish`
}

// The agent runs inside Minecraft server JVMs. Java 21 covers every current Paper release
// (1.20.5+ ships Java 21) and the Paper API artifacts require it. The controller stays on Java 25.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// The Kotlin plugin applies the root toolchain (Java 25); pin Java compilation to 21 so targets
// stay consistent even though this module has no Java sources.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    // Agent plugin code is compiled against the Paper API but only uses stable Bukkit types.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT") // Java 21

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

// Self-contained agent jar: plugin classes + SDK classes (same sourceSet) + kotlin-stdlib, so
// servers need no extra dependencies and no internet access to load it.
val agentJar = tasks.register<Jar>("agentJar") {
    archiveFileName = "VibeCloud-Agent.jar"
    destinationDirectory = layout.buildDirectory.dir("libs-agent")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/versions/**/module-info.class", "module-info.class")
        exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

// Stamp the project version into plugin.yml so the agent reports its real version in heartbeats.
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.build {
    dependsOn(agentJar)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // The installable agent plugin jar is published alongside the library jar with an
            // 'agent' classifier so both can share the same coordinates.
            artifact(agentJar) { classifier = "agent" }
        }
    }
}
