plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mobilekey"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mobilekey"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
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
}
