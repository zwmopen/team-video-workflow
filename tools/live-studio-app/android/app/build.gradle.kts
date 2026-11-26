plugins {
    id("com.android.application")
}

android {
    namespace = "com.zwm.livestudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zwm.livestudio"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("releaseShared") {
            storeFile = file("signing/livestudio.jks")
            storePassword = "livestudio2026"
            keyAlias = "livestudio"
            keyPassword = "livestudio2026"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("releaseShared")
        }
        release {
            signingConfig = signingConfigs.getByName("releaseShared")
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable.add("MissingPermission")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
