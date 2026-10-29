plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    `maven-publish`
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "checkoutkit-ui"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("CheckoutKit UI")
                description.set("Drop-in Jetpack Compose payment sheet (UPI, card, wallet) for CheckoutKit.")
                url.set("https://github.com/Darshan-05032003/CheckoutKit")
            }
        }
    }
    repositories {
        maven {
            name = "localStaging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
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

// Public API lock. AGP 9's built-in Kotlin doesn't apply `org.jetbrains.kotlin.android`, so the
// binary-compatibility-validator plugin can't auto-configure this module (and Kotlin's own ABI
// validation finds no compilation). The validator's tasks are therefore wired by hand to the
// release Kotlin classes: `./gradlew apiDump` writes api/checkoutkit-ui.api, `apiCheck` (part of
// `check`) fails on any binary-incompatible change.
val bcvRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    bcvRuntime("org.ow2.asm:asm:9.6")
    bcvRuntime("org.ow2.asm:asm-tree:9.6")
    bcvRuntime("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
}
val uiApiBuild = tasks.register<kotlinx.validation.KotlinApiBuildTask>("apiBuild") {
    description = "Builds the public API dump of the release classes."
    runtimeClasspath.from(bcvRuntime)
    // Compose emits public synthetic `ComposableSingletons` classes for internal composables.
    ignoredPackages.add("com.darshan.checkoutkit.ui.internal")
    inputClassesDirs.from(tasks.named("compileReleaseKotlin").map { it.outputs.files })
    outputApiFile.set(layout.buildDirectory.file("api/checkoutkit-ui.api"))
}
tasks.register<Sync>("apiDump") {
    description = "Syncs the public API dump into api/."
    group = "other"
    from(uiApiBuild.flatMap { it.outputApiFile })
    into(layout.projectDirectory.dir("api"))
}
val uiApiCheck = tasks.register<kotlinx.validation.KotlinApiCompareTask>("apiCheck") {
    description = "Checks the public API against api/checkoutkit-ui.api."
    group = "verification"
    projectApiFile.set(layout.projectDirectory.file("api/checkoutkit-ui.api"))
    generatedApiFile.set(uiApiBuild.flatMap { it.outputApiFile })
}
tasks.named("check") { dependsOn(uiApiCheck) }
