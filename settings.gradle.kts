pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    // Auto-provisions the JDK toolchain (Java 25) on machines that do not have it installed
    // (CI, JitPack, other development PCs).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "vibecloud"
include("api", "common", "core", "bridge", "launcher")
