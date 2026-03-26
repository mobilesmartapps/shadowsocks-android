plugins {
    id("com.android.library")
    kotlin("android")
}

setupCommon()

android {
    namespace = "com.shadowsocks.sdk"

    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions.isCoreLibraryDesugaringEnabled = true
    buildFeatures.buildConfig = false
}

dependencies {
    api(project(":core"))
    coreLibraryDesugaring(libs.desugar)
}
