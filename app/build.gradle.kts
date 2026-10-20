plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fitalarm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fitalarm.app"
        minSdk = 26          // Android 8.0 이상
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // 외부 라이브러리는 ML Kit 포즈 인식 하나뿐입니다 (모델이 APK에 포함되어 오프라인 동작).
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")

    testImplementation("junit:junit:4.13.2")
}
