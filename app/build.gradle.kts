import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mobilekey"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mobilekey"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ONNX Runtime ships native code per CPU type; every phone MyKey targets is 64-bit ARM.
        ndk { abiFilters += "arm64-v8a" }
    }
    // Release signing key lives outside the repo (~/.android/mykey-release.properties); never commit it.
    val releaseProps = Properties().apply {
        val f = file(System.getProperty("user.home") + "/.android/mykey-release.properties")
        if (f.exists()) f.inputStream().use { this.load(it) }
    }
    signingConfigs {
        if (releaseProps.isNotEmpty()) create("release") {
            storeFile = file(releaseProps.getProperty("storeFile"))
            storePassword = releaseProps.getProperty("storePassword")
            keyAlias = releaseProps.getProperty("keyAlias")
            keyPassword = releaseProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    // Compress ONNX Runtime's native library in the APK (34 MB stored, about 12 MB compressed).
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Styles the password/address suggestions that autofill services show inside the keyboard.
    implementation("androidx.autofill:autofill:1.1.0")
    // Runs the on-device Zenith language model (assets/zenith/zenith_kb.onnx).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
