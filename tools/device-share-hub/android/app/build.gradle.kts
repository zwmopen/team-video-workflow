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
        versionCode = 165
        versionName = "0.8.54"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("signing/gallery-debug.jks")
            storePassword = "gallerydev"
            keyAlias = "gallery-debug"
            keyPassword = "gallerydev"
            // 老机型（华为 P30 / Android 10）只能通过 v1 (JAR) 签名读取安装包签名，
            // v2-only 包会被 UpdatePackageValidator 判定为「安装包没有签名」而拒绝更新。
            enableV1Signing = true
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
