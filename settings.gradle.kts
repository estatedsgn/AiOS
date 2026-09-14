pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
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
