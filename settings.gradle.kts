pluginManagement {
    // Plugin versions live here, not in the root build script.
    //
    // :core applies the Kotlin JVM plugin and :app the Kotlin Android plugin;
    // both ship in the same artifact, so if each module requested its own
    // version Gradle would refuse the second with "already on the classpath
    // with an unknown version". Declaring the versions in pluginManagement
    // resolves them once and lets the modules ask for the plugin id bare.
    //
    // It also keeps the Android Gradle Plugin off the root classpath. Declaring
    // it at the root would drag AGP into every build - including a :core-only
    // one that must work with no access to google() - and applying the Kotlin
    // Android plugin there would then fail on a missing AGP class.
    val kotlinVersion: String by settings
    val agpVersion: String by settings

    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }

    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
        id("org.jetbrains.kotlin.android") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.serialization") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("com.android.application") version agpVersion
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "AiOS"

// The agent's brain is a pure Kotlin/JVM module: it builds and tests anywhere,
// with no Android SDK installed.
include(":core")

// The Android front-end needs the Android SDK. Include it only when an SDK is
// actually present, so `./gradlew :core:test` keeps working on machines (and CI
// runners) that have no SDK. See README.md -> Building.
val localProperties = file("local.properties")
val androidSdkPresent = if (localProperties.exists()) {
    val props = java.util.Properties()
    localProperties.inputStream().use { props.load(it) }
    props.getProperty("sdk.dir") != null
} else {
    System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
}

if (androidSdkPresent) {
    include(":app")
} else {
    logger.lifecycle("AiOS: no Android SDK found - skipping :app. Core module still builds.")
}
