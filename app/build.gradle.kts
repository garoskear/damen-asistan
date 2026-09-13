plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Sürüm her push'ta otomatik artar: commit sayısı (CI'da fetch-depth:0 gerekir).
val commitCount: Int = try {
    ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start().inputStream.bufferedReader().readText().trim().toInt()
} catch (_: Exception) { 1 }

android {
    namespace = "com.damen.asistan"
    compileSdk = 34

    // Sabit imza: her CI derlemesi aynı anahtarla imzalanır, üstüne kurulum çalışır.
    signingConfigs {
        getByName("debug") {
            storeFile = file("damen-debug.keystore")
            storePassword = "android"
            keyAlias = "damen"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("damen-debug.keystore")
            storePassword = "android"
            keyAlias = "damen"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.damen.asistan"
        minSdk = 29
        targetSdk = 34
        versionCode = commitCount
        versionName = "1.0.$commitCount"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")

    // Jetpack Compose
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Gateway WS + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
