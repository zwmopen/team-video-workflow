plugins {
    id("com.android.application")
}

android {
    namespace = "com.zwm.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zwm.gallery"
        minSdk = 26
        targetSdk = 36
        versionCode = 108
        versionName = "0.7.7"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/gallery-debug.jks")
            storePassword = "gallerydev"
            keyAlias = "gallery-debug"
            keyPassword = "gallerydev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }

        release {
            // Keep the existing certificate so installed versions can upgrade in place.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Native WebRTC DataChannel for authenticated P2P transfer; HTTPS relay remains fallback.
    implementation("io.github.webrtc-sdk:android:144.7559.12")
    testImplementation("junit:junit:4.13.2")
    // Android's platform org.json methods are not executable in local JVM tests.
    testImplementation("org.json:json:20240303")
}
