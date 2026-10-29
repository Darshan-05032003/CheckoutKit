plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.bcv)
}

// Coordinates: JitPack serves multi-module builds as com.github.<user>.<repo>:<module>:<tag>, so the
// group is set to exactly that (the Gradle module metadata then needs no rewriting). The version
// comes from `-Pversion=<tag>` (jitpack.yml passes $VERSION); local builds get a snapshot version.
allprojects {
    group = "com.github.Darshan-05032003.CheckoutKit"
    if (version == Project.DEFAULT_VERSION) version = "1.0.0-SNAPSHOT"
}

// Public API lock (in addition to explicit API mode): `./gradlew apiDump` writes api/*.api,
// `apiCheck` (part of `check`) fails on any binary-incompatible change.
apiValidation {
    ignoredProjects += listOf("sample")
    nonPublicMarkers += listOf("kotlin.PublishedApi")
}
