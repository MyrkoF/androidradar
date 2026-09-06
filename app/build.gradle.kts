plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ch.lab77.radar"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.lab77.radar"
        minSdk = 33
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.2"
    }

    signingConfigs {
        // Clé de release fournie par la CI (secrets GitHub, cf. docs/RELEASE.md). Absente en local : APK non signé.
        val ksPath = System.getenv("RELEASE_KEYSTORE_PATH")
        if (ksPath != null) create("release") {
            storeFile = file(ksPath)
            storeType = "PKCS12"
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Carte : MapLibre Native (BSD-2) ; seul le package map/ touche au réseau (cahier §8)
    implementation("org.maplibre.gl:android-sdk:13.6.0")
    implementation("org.maplibre.gl:android-sdk-turf:6.0.1")
    testImplementation("junit:junit:4.13.2")
}
