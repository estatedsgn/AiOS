import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Java 17 bytecode, compiled by whatever JDK (17+) is on the machine. Pinned
// rather than left to default because :app consumes this module and the Android
// Gradle Plugin rejects class files newer than 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlin.coroutines.core)
    api(libs.kotlin.serialization.json)
    api(libs.anthropic.java)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
