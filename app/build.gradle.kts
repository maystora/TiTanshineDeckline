plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.deckline.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.deckline.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // A fixed signing key, so every new build installs as an update over the previous one.
    signingConfigs {
        create("deckline") {
            storeFile = file("deckline.keystore")
            storePassword = "deckline"
            keyAlias = "deckline"
            keyPassword = "deckline"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("deckline") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("deckline")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
