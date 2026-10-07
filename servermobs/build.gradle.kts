plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") version "9.6.1"
}

// ServerMobs runs inside Minecraft server JVMs alongside the agent, so it targets Java 21
// (every current Paper release ships Java 21) rather than the controller's Java 25.
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
    // Minecraft server API and its bundled libraries (adventure, gson) are provided by the
    // running server, never shipped inside the plugin jar.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // PacketEvents powers the fake-player NPC entities and the interaction listener. It is
    // shaded (and relocated) into the plugin so admins install nothing else; adventure is left
    // out so both our code and PacketEvents use the server's copy (Paper provides adventure).
    implementation("com.github.retrooper:packetevents-spigot:2.14.0") {
        exclude(group = "net.kyori")
    }
    implementation("org.yaml:snakeyaml:2.7")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

// Self-contained plugin jar: our classes + shaded PacketEvents + kotlin-stdlib + snakeyaml.
// PacketEvents is relocated so it can never clash with another plugin bundling an unrelocated
// copy. Adventure and gson are intentionally NOT bundled (the server provides them), which keeps
// our code interoperable with Paper's net.kyori types.
tasks.shadowJar {
    archiveFileName.set("ServerMobs.jar")
    archiveClassifier.set("")
    // EXCLUDE keeps the plugin descriptor unambiguous: packetevents-spigot ships its own
    // plugin.yml, and our top-level descriptor (processed first) must be the one that survives.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/versions/**/module-info.class", "module-info.class")
    exclude("META-INF/LICENSE*", "META-INF/NOTICE*")
    exclude("net/kyori/**")
    relocate("com.github.retrooper", "dev.vibecloud.servermobs.libs.packetevents")
    relocate("io.github.retrooper", "dev.vibecloud.servermobs.libs.packetevents")
}

// Stamp the project version into plugin.yml so the plugin reports its real version.
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
