pluginManagement {
    val quarkusPluginVersion: String by settings
    val quarkusPluginId: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
    plugins {
        id(quarkusPluginId) version quarkusPluginVersion
    }
}

// Auto-provision the JDK toolchain declared in build.gradle.kts (Java 21).
// If JDK 21 is already installed locally it is used as-is; otherwise Gradle
// downloads a matching JDK via the Foojay Disco API. This keeps local dev and
// CI from failing with a confusing "toolchain not found" when only an older
// JDK is on the PATH.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "chatllm-quarkus"
