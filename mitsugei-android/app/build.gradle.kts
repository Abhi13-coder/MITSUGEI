plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mitsugei.app"
    compileSdk = 36  // Android 16 — targetSdk 36 became mandatory for Play submissions Aug 31 2026

    defaultConfig {
        applicationId = "com.mitsugei.app"
        minSdk = 26          // Android 8.0 — matches the Redmi 9A this is built for
        targetSdk = 36       // Android 16, current mandatory target as of Sept 2026
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 32-bit ARM (armeabi-v7a) is the actual target device (Redmi 9A);
        // arm64-v8a kept too so this also runs on 64-bit test devices/emulators.
        // This is exactly the ABI list snappy-java and zstd-jni ship prebuilt
        // native libs for (unlike DuckDB/PyArrow/Chaquopy's Python 3.12+
        // builds, which do NOT ship armeabi-v7a anymore — see mitsugeidb's
        // package docs for why this app doesn't use either).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking — mitsugeidb's HTTP-range Parquet reads, verticals, and
    // datasets-server diagnostics all go through this. No server of ours.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // mitsugeidb: Parquet page decompression. Both ship real armeabi-v7a
    // native libs — this is the actual reason mitsugeidb works on a 32-bit
    // device where DuckDB/Chaquopy-Python don't. Verify current versions
    // at search.maven.org before a real release; these were current as of
    // this build.
    implementation("org.xerial.snappy:snappy-java:1.1.10.5")
    implementation("com.github.luben:zstd-jni:1.5.6-3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
