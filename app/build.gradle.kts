plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 출시용(업로드) 서명 정보는 저장소에 넣지 않고 환경변수로만 받습니다.
 * GitHub Actions 에서는 저장소 Secrets 로 넘겨주고, 없으면 release 빌드는 서명하지 않습니다.
 */
val uploadKeystore: String? = System.getenv("FITALARM_UPLOAD_KEYSTORE")
val hasUploadKey = !uploadKeystore.isNullOrBlank() && file(uploadKeystore!!).exists()

android {
    namespace = "com.fitalarm.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fitalarm.app"
        minSdk = 26          // Android 8.0 이상
        targetSdk = 36       // Google Play: 2026-08-31 부터 신규 앱·업데이트는 Android 16(API 36) 이상
        versionCode = 4
        versionName = "2.2.0"
    }

    signingConfigs {
        // 테스트용 APK. 빌드마다 키가 바뀌면 새 APK를 기존 앱 위에 설치할 수 없으므로 고정 키 사용 (출시에는 쓰지 않음)
        getByName("debug") {
            storeFile = file("fitalarm-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(uploadKeystore!!)
                storePassword = System.getenv("FITALARM_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("FITALARM_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("FITALARM_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // 출시 빌드의 lint 검사 결과는 보고만 하고 빌드를 막지 않음 (실기기 테스트로 확인)
        abortOnError = false
    }
}

dependencies {
    // 외부 라이브러리는 ML Kit 포즈 인식 하나뿐입니다 (모델이 APK에 포함되어 오프라인 동작).
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")

    testImplementation("junit:junit:4.13.2")
}
