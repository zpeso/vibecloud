plugins {
    kotlin("jvm")
    `maven-publish`
    id("com.gradleup.shadow") version "9.6.1"
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
    // Redis transport shared with the controller: the gateway, the key layout and the JSON
    // helpers. Jedis comes in through it and is shaded into the agent jar.
    implementation(project(":transport"))
    // Jedis logs through SLF4J; a server JVM has no binding for our shaded copy, so ship a no-op
    // one instead of letting SLF4J print its "no binding" warning into every server log.
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // Agent plugin code is compiled against the Paper API but only uses stable Bukkit types.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT") // Java 21

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

// Published SDK jar: plugin classes + the Redis transport + Jedis, so a plugin that bundles the
// SDK needs exactly one dependency. The transport types are not part of the public surface; they
// are shaded (and Jedis/its pool relocated) to keep the artifact self-contained — JitPack would
// otherwise have to resolve a sibling module that is not published under `dev.vibecloud`.
tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("bridge-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/versions/**/module-info.class", "module-info.class")
    exclude("META-INF/LICENSE*", "META-INF/NOTICE*")
    relocate("redis.clients", "dev.vibecloud.api.bridge.libs.jedis")
    relocate("org.apache.commons.pool2", "dev.vibecloud.api.bridge.libs.pool2")
}

// The unshaded jar keeps a classifier so build/libs never holds two jars with the same name.
tasks.jar {
    archiveClassifier.set("plain")
}

// Self-contained agent jar: plugin classes + SDK classes (same sourceSet) + kotlin-stdlib, so
// servers need no extra dependencies and no internet access to load it.
val agentJar = tasks.register<Jar>("agentJar") {
    archiveFileName = "VibeCloud-Agent.jar"
    destinationDirectory = layout.buildDirectory.dir("libs-agent")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // The shaded runtime classpath includes project jars (transport); declare the dependency
    // explicitly so Gradle 9 never evaluates the zipTree before they are built.
    dependsOn(configurations.runtimeClasspath)
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
            // The shaded jar IS the library artifact, so the published POM declares no
            // dependencies (everything it needs is inside). Publishing the java component
            // instead would advertise `dev.vibecloud:transport`, which consumers cannot resolve.
            artifact(tasks.shadowJar)
            artifact(tasks.named("sourcesJar"))
            // The installable agent plugin jar is published alongside the library jar with an
            // 'agent' classifier so both can share the same coordinates.
            artifact(agentJar) { classifier = "agent" }
        }
    }
}
