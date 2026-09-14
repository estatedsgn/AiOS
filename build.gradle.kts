// Every Kotlin plugin version is declared once, here, and applied in the
// modules that need it.
//
// This is not stylistic. :core applies kotlin.jvm and :app applies
// kotlin.android; both come from the same kotlin-gradle-plugin artifact, so if
// the second one carries a version that the root never declared, Gradle finds
// the artifact already on the classpath "with an unknown version" and fails the
// build. Declaring them together keeps the versions resolvable as one set.
//
// The Android Gradle Plugin is deliberately NOT declared here: it resolves from
// google(), which is unreachable in some environments, and the root script is
// evaluated even when only :core is being built. :app owns it instead, and :app
// is only included when an Android SDK is present.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
