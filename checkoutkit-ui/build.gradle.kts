plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.darshan.checkoutkit.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    api(project(":checkoutkit-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// UI stress tests live in the `stress` package; they are excluded from the normal unit test run
// and executed by `./gradlew stressTest` (together with the core's stress suite).
val uiStressPackage = "com.darshan.checkoutkit.ui.stress.*"

tasks.withType<Test>().configureEach {
    if (name != "stressTest") filter { excludeTestsMatching(uiStressPackage) }
}

tasks.register<Test>("stressTest") {
    description = "Runs the payment-sheet stress simulations."
    group = "verification"
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTest.dependsOn)
    testClassesDirs = unitTest.testClassesDirs
    classpath = unitTest.classpath
    filter { includeTestsMatching(uiStressPackage) }
    maxHeapSize = "1g"
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}
