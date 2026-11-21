// A minimal third-party app that shares a photo with Floridex, used only to
// verify "Identificar con Floridex" end to end (scripts/probar-compartir.sh).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "es.floridex.emisora"
    compileSdk = 34
    defaultConfig {
        applicationId = "es.floridex.emisora"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
