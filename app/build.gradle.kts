// ── Version ──────────────────────────────────────────────────────────────
// Single source of truth. Edit this line only.
val appVersionName = "1.8.1"

/**
 * major.minor.patch -> major*10000 + minor*100 + patch
 *
 * Monotonic as long as minor and patch stay under 100, which the require()
 * below enforces at configuration time -- a build failure is far cheaper than
 * discovering the problem when Play rejects the upload.
 */
fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map { it.trim().toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    require(minor < 100 && patch < 100) {
        "versionName '$name': minor and patch must each be < 100 for the " +
                "versionCode scheme to stay monotonic."
    }
    return major * 10000 + minor * 100 + patch
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // FIXED: was id("com.google.devtools.ksp") with the version declared
    // separately in the root build file. The catalog already defines this
    // plugin; using the alias means the KSP version lives in exactly one
    // place and can't silently drift out of lockstep with `kotlin`.
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.abhinavxt.novelforge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abhinavxt.novelforge"
        minSdk = 24
        targetSdk = 36

        // Derived, not hand-maintained. versionCode had drifted to 2 while
        // versionName was already "1.8.0" -- the classic failure mode, because
        // the two are edited in different places at different times and nothing
        // enforces a relationship. Deriving one from the other makes drift
        // impossible: bump appVersionName and the code follows.
        //
        // 1.8.0 -> 10800.  Leaves room for 99 minors and 99 patches per major,
        // and stays far below Play's 2100000000 ceiling.
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Sherpa-ONNX native libs.
        // NOTE: armeabi-v7a is deliberately absent, which means 32-bit ARM
        // devices cannot install this build — yet minSdk = 24 advertises
        // support back to Android 7. Pick one: add "armeabi-v7a" (if the
        // Sherpa AAR ships that slice) or raise minSdk to ~26.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            // Still false — flip this only once the R8 keep rules in
            // proguard-rules.pro are in place AND you've run a release
            // build end-to-end (backup restore, TTS playback, source
            // scraping). See the review for why each rule is required.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // --- Compose BOM must come first so the artifacts below inherit it ---
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.datastore.preferences)

    // FIXED: okhttp was declared three times — once via the catalog and
    // twice as an identical literal. Same version, so no conflict today,
    // but a catalog bump would have been silently overridden by the
    // literals. One declaration, catalog-owned.
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    implementation(libs.gson)

    // Sherpa-ONNX AAR. Unversioned and unreproducible — a fresh clone with
    // an empty libs/ compiles to a broken APK with no error until runtime
    // UnsatisfiedLinkError. Worth moving to a real Maven coordinate or at
    // minimum committing a checksum.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}