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
    // PacketEvents (shaded into the ServerMobs plugin) is published here, not on Maven Central.
    maven("https://repo.codemc.io/repository/maven-releases/")
}
}

plugins {
    // Auto-provisions the JDK toolchain (Java 25) on machines that do not have it installed
    // (CI, JitPack, other development PCs). 1.0.0 is required for Gradle 9: older releases
    // reference a JvmVendorSpec member that Gradle 9 removed, which made JitPack builds fail
    // with "JvmVendorSpec does not have member field 'IBM_SEMERU'" (only there, because JitPack
    // is the one environment that must actually provision the missing toolchain).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vibecloud"
include("api", "common", "core", "bridge", "servermobs", "launcher")
