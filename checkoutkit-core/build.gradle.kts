plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kover)
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm()

    android {
        namespace = "com.darshan.checkoutkit.core"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

// Stress tests live in the `stress` package of jvmTest. They are excluded from the normal
// jvmTest/allTests run and executed only by `./gradlew stressTest`.
val stressPackage = "com.darshan.checkoutkit.core.stress.*"

tasks.named<Test>("jvmTest") {
    filter { excludeTestsMatching(stressPackage) }
}

val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")

tasks.register<Test>("stressTest") {
    description = "Runs the load/stress simulations (10k sessions, retry storms, idempotency races)."
    group = "verification"
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    filter { includeTestsMatching(stressPackage) }
    maxHeapSize = "1g"
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}

kover {
    reports {
        verify {
            rule("SDK core line coverage") {
                minBound(80)
            }
        }
    }
}
