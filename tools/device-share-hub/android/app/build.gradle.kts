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
        versionCode = 10
        versionName = "0.3.7"
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
    testImplementation("junit:junit:4.13.2")
}
