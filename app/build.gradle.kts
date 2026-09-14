import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versions come from settings.gradle.kts. AGP must be applied before the
    // Kotlin Android plugin, which reads AGP's extension.
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.aios.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.aios.app"
        // 26 is the floor for the notification channels and the java.util.Optional
        // usage that reaches us through the Anthropic SDK.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The Anthropic SDK reaches for java.time and friends; desugaring keeps
        // that working below API 34.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    packaging {
        resources {
            // Jackson and OkHttp each ship metadata that collides on merge.
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

kotlin {
    compilerOptions {
        // Matches :core and android.compileOptions above. The old
        // kotlinOptions{} DSL is removed in Kotlin 2.x, not merely deprecated.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
