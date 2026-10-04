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

// ---------------------------------------------------------------------------
// Dashboard asset obfuscation (release builds only)
//
// Dev builds ship the readable dashboard source; release builds (and CI, which
// runs the release task) minify/mangle dashboard/app.js with terser so the exact
// dashboard code is not shipped in plain text. Terser runs via `npx --yes` from
// the Node toolchain (preinstalled on ubuntu CI runners); the result is verified
// with `node --check` so a broken transform can never be packaged.
// ---------------------------------------------------------------------------
val obfuscateDashboard = gradle.startParameter.taskNames.any { it.removePrefix(":") == "release" } ||
    gradle.startParameter.projectProperties.containsKey("release") ||
    providers.environmentVariable("CI").isPresent

val obfuscateAppJs = tasks.register("obfuscateAppJs") {
    group = "distribution"
    description = "Minifies and mangles dashboard/app.js inside the built resources (release builds only)."
    dependsOn(tasks.processResources)
    onlyIf { obfuscateDashboard }
    doLast {
        val target = layout.buildDirectory.file("resources/main/dashboard/app.js").get().asFile
        if (!target.isFile) return@doLast
        val npx = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npx.cmd" else "npx"
        // Streams stay separate: npx prints notices ("npm notice ...") on stderr, and anything
        // merged into stdout here would end up inside the shipped app.js.
        val proc = ProcessBuilder(
            listOf(
                npx, "--yes", "terser@5.37.0", target.absolutePath,
                "--compress", "--mangle", "--comments", "false", "--format", "ascii_only=true",
            ),
        ).start()
        val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val warnings = proc.errorStream.readBytes().toString(Charsets.UTF_8)
        val code = proc.waitFor()
        check(code == 0) { "terser failed ($code): ${(warnings + output).take(500)}" }
        check(output.isNotBlank()) { "terser produced empty output" }

        // Sanity-check the transformed script with Node before packaging it.
        val tmp = File(target.parentFile, "app.obfuscated.js")
        tmp.writeText(output)
        val node = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "node.exe" else "node"
        val check = ProcessBuilder(listOf(node, "--check", tmp.absolutePath))
            .redirectErrorStream(true).start()
        val checkOut = check.inputStream.readBytes().toString(Charsets.UTF_8)
        check.waitFor()
        check(check.exitValue() == 0) { "obfuscated dashboard JS failed node --check: ${checkOut.take(500)}" }

        val before = target.length()
        target.writeText(output)
        tmp.delete()
        println("Obfuscated dashboard/app.js (${before} → ${target.length()} bytes)")
    }
}

tasks.named("jar") {
    dependsOn(obfuscateAppJs)
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
