import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // 패키지 이름은 가안. 앱마켓 등록 전에 확정한다.
    namespace = "kr.mobility.mirichaeum.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.mobility.mirichaeum"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
        // 실제 데이터 API 키. release에는 넣지 않는다(공개 저장소의 APK에서 키가 새지 않게, A-07).
        buildConfigField("String", "KAKAO_REST_KEY", "\"\"")
        buildConfigField("String", "DATA_GO_KR_KEY", "\"\"")
        buildConfigField("String", "OPINET_KEY", "\"\"")
    }
    buildTypes {
        debug {
            val keys = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
            listOf("KAKAO_REST_KEY", "DATA_GO_KR_KEY", "OPINET_KEY").forEach { name ->
                buildConfigField("String", name, "\"${keys.getProperty(name, "")}\"")
            }
        }
        release {
            isMinifyEnabled = false
            // 심사용 APK는 디버그 서명으로 만든다(배포 서명은 앱마켓 등록 때 정한다).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    useLibrary("android.car")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine:mirichaeum"))
    implementation(project(":platform:pleos"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20240303")
}
