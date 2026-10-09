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
        versionCode = 184
        versionName = "0.8.73"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/gallery-debug.jks")
            storePassword = "gallerydev"
            keyAlias = "gallery-debug"
            keyPassword = "gallerydev"
            // 老机型（华为 P30 / Android 10）依赖 v1 (JAR) 签名，
            // 现代机型（Android 11~15）强制要求 v2/v3 块签名，全链路开启避免杀软拦截
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
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

    lint {
        disable.add("PropertyEscape")
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's platform org.json methods are not executable in local JVM tests.
    testImplementation("org.json:json:20240303")
}
