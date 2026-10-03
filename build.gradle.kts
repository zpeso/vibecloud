import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.plugins.JavaPluginExtension

plugins {
    kotlin("jvm") version "2.4.20" apply false
}

// Release version lives in gradle.properties. The `release` task overrides it with
// -Prelease=X.Y.Z for the release build and persists the bump in the release commit,
// so jars/zip/plugin.yml are all stamped with the new version in the same build.
val releaseVersion = findProperty("release") as String?

allprojects {
    group = "dev.vibecloud"
    releaseVersion?.let { version = it }
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = 1
        // Keep the test worker small; constrained hosts may run Gradle and a child JVM together.
        maxHeapSize = "64m"
        jvmArgs("-XX:MaxMetaspaceSize=128m", "-XX:+UseSerialGC", "-Xss512k")
    }

    // Publishing hygiene for the modules that publish (api, bridge): ship a sources jar and
    // complete POM metadata so JitPack/Maven consumers get IDE sources and valid coordinates.
    plugins.withId("maven-publish") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("VibeCloud ${project.name}")
                    description.set(
                        when (project.name) {
                            "api" -> "Public models, manager interfaces and lifecycle events for embedding VibeCloud"
                            "bridge" -> "VibeCloud SDK for Minecraft plugins: the VibeCloud facade and bridge HTTP client"
                            else -> "VibeCloud — a single-node Minecraft cloud controller"
                        },
                    )
                    url.set("https://github.com/zpeso/vibecloud")
                    developers {
                        developer {
                            id.set("zpeso")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/zpeso/vibecloud.git")
                        developerConnection.set("scm:git:ssh://github.com/zpeso/vibecloud.git")
                        url.set("https://github.com/zpeso/vibecloud/tree/master")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One-command release
//
//   ./gradlew release -Prelease=X.Y.Z
//
// runs every module's tests, builds build/dist/vibecloud-X.Y.Z.zip, commits all
// pending changes plus the version bump ("Release X.Y.Z"), tags vX.Y.Z and
// pushes both. The pushed tag triggers GitHub Actions (.github/workflows/release.yml),
// which attaches the zip to a GitHub Release and kicks JitPack so the published
// API artifacts (com.github.zpeso.vibecloud:...) are rebuilt from exactly this tag.
// ---------------------------------------------------------------------------

fun runGit(vararg args: String): String {
    val proc = ProcessBuilder(listOf("git", *args))
        .directory(rootProject.projectDir)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    val err = proc.errorStream.bufferedReader().readText()
    val code = proc.waitFor()
    check(code == 0) { "git ${args.joinToString(" ")} failed ($code):\n$err" }
    return out.trim()
}

val persistedVersion = rootProject.file("gradle.properties").readLines()
    .firstOrNull { it.startsWith("version=") }
    ?.substringAfter("=")?.trim()
    ?: throw GradleException("gradle.properties is missing a 'version=' entry")

if (gradle.startParameter.taskNames.any { it.removePrefix(":") == "release" }) {
    if (releaseVersion == null) {
        throw GradleException("Usage: ./gradlew release -Prelease=X.Y.Z")
    }
    if (!Regex("\\d+\\.\\d+\\.\\d+([-+][A-Za-z0-9.-]+)?").matches(releaseVersion)) {
        throw GradleException("-Prelease must be semver like 1.2.3, got '$releaseVersion'")
    }
    if (releaseVersion == persistedVersion) {
        throw GradleException("Version $releaseVersion is already the current version — pick a new one.")
    }
}

tasks.register("release") {
    group = "distribution"
    description = "Tests, zip, version bump, commit, tag vX.Y.Z, push. Usage: ./gradlew release -Prelease=X.Y.Z"
    dependsOn(
        ":api:test", ":common:test", ":core:test", ":bridge:test", ":launcher:test",
        ":launcher:releaseZip",
    )
    doLast {
        val version = releaseVersion ?: throw GradleException("release requires -Prelease=X.Y.Z")
        val zip = rootProject.layout.buildDirectory.file("dist/vibecloud-$version.zip").get().asFile
        if (!zip.isFile) throw GradleException("Release zip missing: ${zip.absolutePath}")

        // Persist the version bump (picked up by every later build).
        val propsFile = rootProject.file("gradle.properties")
        val lines = propsFile.readLines().toMutableList()
        val idx = lines.indexOfFirst { it.startsWith("version=") }
        if (idx >= 0) lines[idx] = "version=$version" else lines.add("version=$version")
        propsFile.writeText(lines.joinToString("\n") + "\n")

        // Commit everything (feature work + bump) so the tag always matches what was tested.
        runGit("add", "-A")
        if (runGit("status", "--porcelain").isNotEmpty()) {
            runGit(
                "commit", "-m", "Release $version",
                "-m", "Generated with Codebuff 🤖\nCo-Authored-By: Codebuff <noreply@codebuff.com>",
            )
        }

        // Tag HEAD (retry-safe: an existing tag at HEAD is fine, a moved tag is not).
        val tag = "v$version"
        val taggedCommit = runCatching {
            runGit("rev-parse", "--verify", "--quiet", "$tag^{commit}")
        }.getOrNull()
        if (taggedCommit != null && taggedCommit != runGit("rev-parse", "HEAD")) {
            throw GradleException("Tag $tag already exists on a different commit — pick a new version or delete the tag first.")
        }
        runGit("tag", "-f", tag)

        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        runGit("push", "origin", branch)
        runGit("push", "origin", tag)

        println()
        println("=============================================================")
        println(" Released VibeCloud $version")
        println("   zip:     ${zip.absolutePath}")
        println("            (scp to the VM, restart cloud + services)")
        println("   tag:     $tag pushed — CI attaches the zip to the GitHub")
        println("            Release and kicks JitPack so the API artifacts")
        println("            are rebuilt from this exact tag")
        println("   JitPack: https://jitpack.io/#zpeso/vibecloud/$tag")
        println("=============================================================")
    }
}
