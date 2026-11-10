plugins {
    id("com.android.application")
}

android {
    namespace = "com.haoverlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.haoverlay"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-only app: sign release with the debug key so it installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The icon font is already compressed; keep it stored so Typeface.createFromAsset is fast.
        noCompress += listOf("ttf")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
