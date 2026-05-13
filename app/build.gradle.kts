import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read local.properties
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    FileInputStream(localPropsFile).use { localProps.load(it) }
}

android {
    namespace = "com.shawtung.mobileclicker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shawtung.mobileclicker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Inject ntfy topic into BuildConfig
        buildConfigField("String", "NTFY_TOPIC", "\"${localProps.getProperty("ntfy.topic") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Google ML Kit Text Recognition (Chinese)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Shizuku (13.1.0 - newProcess still public)
    implementation("dev.rikka.shizuku:api:13.1.0")
    implementation("dev.rikka.shizuku:provider:13.1.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
