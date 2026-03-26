plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.example.shadowsockssample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.shadowsockssample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }

    packagingOptions {
        jniLibs.useLegacyPackaging = true
        // Avoid duplicate META-INF files from transitive deps
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    // ── Local SDK AARs ────────────────────────────────────────────────────
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // ── Transitive deps required by shadowsocks-sdk / core / plugin ───────
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // AndroidX
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-livedata-core-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")

    // WorkManager (multi-process required by core)
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.work:work-multiprocess:2.11.0")

    // Material
    implementation("com.google.android.material:material:1.13.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Firebase (required by core)
    implementation(platform("com.google.firebase:firebase-bom:33.15.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")

    // Play Services
    implementation("com.google.android.gms:play-services-oss-licenses:17.1.0")

    // DNS + Logging
    implementation("dnsjava:dnsjava:3.6.3")
    implementation("com.jakewharton.timber:timber:5.0.1")
}
