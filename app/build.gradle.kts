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
        versionCode = 7
        versionName = "2.4.1"
        // 광고: 기본은 Google 테스트 광고. 출시(release) 빌드만 실제 광고 단위를 씀 (AdMobIds)
        buildConfigField("boolean", "ADS_TEST_MODE", "true")
    }

    buildFeatures {
        buildConfig = true
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
            // R8 로 코드 축소·난독화 (코드 분석·위변조를 어렵게 하고 용량도 줄임)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
            // Play 스토어에 올리는 빌드만 실제 광고
            buildConfigField("boolean", "ADS_TEST_MODE", "false")
        }
        // 출시(release)와 똑같이 난독화한 테스트용 빌드. 테스트 키로 서명해 기존 테스트 앱 위에 덮어 설치 가능
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            // 폰에 직접 설치해 보는 빌드라 테스트 광고만 (내 앱의 실제 광고를 누르면 AdMob 계정이 정지될 수 있음)
            buildConfigField("boolean", "ADS_TEST_MODE", "true")
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
    // ML Kit 포즈 인식 (모델이 APK에 포함되어 오프라인 동작)
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    // 광고: Google 모바일 광고 SDK(차세대, GMA Next-Gen) + 광고 동의(UMP, 유럽 등 동의가 필요한 지역에서만 동의 화면 표시)
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.6.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    testImplementation("junit:junit:4.13.2")
}
