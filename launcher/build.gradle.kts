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

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}

// The bridge agent jar ships inside the distribution (lib/) so the controller can auto-install it
// into every backend service. Built by :bridge:agentJar and copied into the install directory.
val agentJarFile = layout.projectDirectory.file("../bridge/build/libs-agent/VibeCloud-Agent.jar")
val syncAgentJar = tasks.register<Copy>("syncAgentJar") {
    group = "distribution"
    description = "Copies the VibeCloud agent plugin jar into the install directory."
    dependsOn(":bridge:agentJar")
    from(agentJarFile)
    into(File(installRoot, "lib"))
}
tasks.named("installDist") { finalizedBy(syncAgentJar) }

// Linux-friendly alias: the Gradle unix start script is generated without an extension, which is
// easy to miss next to vibecloud.bat. Ship the identical script as vibecloud.sh too.
val addLinuxScript = tasks.register<Copy>("addLinuxScript") {
    group = "distribution"
    description = "Publishes the unix start script additionally as bin/vibecloud.sh."
    dependsOn(tasks.named("installDist"))
    from(File(installRoot, "bin/vibecloud"))
    into(File(installRoot, "bin"))
    rename { "vibecloud.sh" }
}

application {
    applicationName = "vibecloud"
    mainClass.set("dev.vibecloud.launcher.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Pin the start scripts' classpath to the lib/ wildcard instead of exact jar names: after a
// self-update replaces jars, an old script would otherwise reference deleted versions and the
// launcher could silently start from a stale installation. 'lib/*' always loads what is there.
tasks.named<org.gradle.api.tasks.application.CreateStartScripts>("startScripts") {
    doLast {
        val unix: java.io.File = unixScript
        unix.writeText(
            unix.readText().replace(Regex("CLASSPATH=\\\$APP_HOME/lib/.*")) { "CLASSPATH=\$APP_HOME/lib/*" },
        )
        val windows: java.io.File = windowsScript
        windows.writeText(
            windows.readText().replace(Regex("set CLASSPATH=.*")) { "set CLASSPATH=%APP_HOME%\\lib\\*" },
        )
    }
}

// Stamp the release version into the launcher so the self-updater knows what it is running.
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("version.properties") {
        expand(props)
    }
}

// Self-contained release archive: config, secret, scripts, libs at the top level so the extracted
// folder is the working root and can be moved anywhere (paths resolve relative to config.yml).
val runtimeConfigDir = layout.buildDirectory.dir("runtime-config").get().asFile
val installRoot = layout.buildDirectory.dir("install/vibecloud").get().asFile
val distDir = rootProject.layout.buildDirectory.dir("dist").get().asFile

val runtimeConfig = tasks.register<Copy>("runtimeConfig") {
    from(rootProject.file("config.example.yml"))
    rename { "config.yml" }
    into(runtimeConfigDir)
}

tasks.register<Zip>("releaseZip") {
    group = "distribution"
    description = "Builds a self-contained VibeCloud release archive."
    archiveFileName.set("vibecloud-${project.version}.zip")
    destinationDirectory.set(distDir)
    dependsOn(tasks.named("installDist"))
    dependsOn(runtimeConfig)
    dependsOn(syncAgentJar)
    dependsOn(addLinuxScript)
    from(rootProject.file("README.md"))
    from(rootProject.file("LICENSE"))
    from(rootProject.file("docs/API.md")) { into("docs") }
    from(runtimeConfigDir)
    from(installRoot) {
        exclude("**/simple-cloud*", "**/config.yml", "bin/**")
    }
    // Start scripts must keep their executable bit: unzip on Linux restores the Unix mode
    // stored in the zip, so store bin/ as 0755 (Windows filesystems lose it otherwise).
    from(installRoot) {
        include("bin/**")
        // Store the start scripts with the executable bit so `unzip` on Linux restores them as
        // runnable (Windows filesystems cannot represent the bit, so it must be set here).
        filePermissions { unix("rwxr-xr-x") } // 0755
    }
}
