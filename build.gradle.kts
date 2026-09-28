plugins {
    kotlin("jvm") version "2.4.20" apply false
}

allprojects {
    group = "dev.vibecloud"
    version = "0.1.0"
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = 1
        // Keep the test worker small; constrained hosts may run Gradle and a child JVM together.
        maxHeapSize = "64m"
        jvmArgs("-XX:MaxMetaspaceSize=128m", "-XX:+UseSerialGC", "-Xss512k")
    }
}
