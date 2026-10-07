import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") version "9.6.1"
}

// Minecraft 1.8 servers commonly run Java 8, so this build targets 1.8 bytecode. The shared
// sources (../servermobs-common) are written to the Java 8 API for exactly this reason.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
}

// Compile the platform-independent implementation (commands, packet NPCs, persistence, config,
// skin resolution) that is shared with the modern build.
sourceSets {
    main {
        kotlin.srcDir("../servermobs-common/src/main/kotlin")
    }
}

dependencies {
    // The 1.8 Spigot API. Adventure is NOT provided by a 1.8 server, so (unlike the modern build)
    // it is bundled and relocated below.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")

    implementation("com.github.retrooper:packetevents-spigot:2.14.0")
    // PacketEvents pulls adventure-api/nbt/key but not these; needed for our text rendering.
    implementation("net.kyori:adventure-text-minimessage:4.26.1")
    implementation("net.kyori:adventure-text-serializer-legacy:4.26.1")

    implementation("org.yaml:snakeyaml:2.7")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

// Self-contained 1.8 plugin jar. Both PacketEvents and adventure are relocated so the bundled
// copies never clash with anything else on the server.
tasks.shadowJar {
    archiveFileName.set("ServerMobs-1.8.jar")
    archiveClassifier.set("")
    // EXCLUDE keeps our plugin.yml (processed first) rather than a dependency's copy.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/versions/**/module-info.class", "module-info.class")
    exclude("META-INF/LICENSE*", "META-INF/NOTICE*")
    relocate("com.github.retrooper", "dev.vibecloud.servermobs.libs.packetevents")
    relocate("io.github.retrooper", "dev.vibecloud.servermobs.libs.packetevents")
    relocate("net.kyori", "dev.vibecloud.servermobs.libs.kyori")
}

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
