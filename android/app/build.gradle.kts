import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The app data and the model live once, in ../shared (the web app reads the
// same files). They are copied into generated assets at build time:
//   floridex.json, sprites/, miniaturas/ (reference photos), modelo/
val datosCompartidos = layout.buildDirectory.dir("generated/floridex/assets")
val copiarDatos by tasks.registering(Sync::class) {
    val shared = rootProject.file("../shared")
    from(shared.resolve("floridex.json"))
    from(shared.resolve("sprites")) { into("sprites") }
    from(shared.resolve("miniaturas2")) { include("*.webp"); into("miniaturas") }
    from(shared.resolve("modelo")) { include("config.json"); into("modelo") }
    from(shared.resolve("modelo/onnx")) { include("model_quantized.onnx"); into("modelo") }
    into(datosCompartidos)
}
tasks.named("preBuild") { dependsOn(copiarDatos) }

android {
    namespace = "es.floridex"
    compileSdk = 34

    defaultConfig {
        applicationId = "es.floridex"
        minSdk = 26
        targetSdk = 34
        // release-please bumps this line; versionCode follows it (1.2.3 -> 10203).
        versionName = "0.1.0" // x-release-please-version
        versionCode = versionName!!.split(".").map(String::toInt).let { (a, b, c) -> a * 10000 + b * 100 + c }.coerceAtLeast(1)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: with android/keystore.properties (never committed:
    // storeFile, storePassword, keyAlias, keyPassword) the release APKs are
    // signed with that key; without it, with the local debug key, so they
    // still install (fine for testing, not for publishing updates).
    val claves = rootProject.file("keystore.properties")
    signingConfigs {
        if (claves.exists()) {
            create("publicacion") {
                val p = Properties().apply { claves.inputStream().use { load(it) } }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("publicacion") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += "onnx" }
    sourceSets.getByName("main").assets.srcDir(datosCompartidos)
    // One APK per CPU family: ONNX Runtime ships a native library per ABI and
    // bundling them all makes a single 109 MB file.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
