import java.util.Properties

plugins {
    // AGP 9 đã tích hợp sẵn Kotlin, không cần plugin org.jetbrains.kotlin.android.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

val facebookAppId = System.getenv("FACEBOOK_APP_ID")
    ?: localProperties.getProperty("FACEBOOK_APP_ID")
    ?: "SOCIAL_AGENT_FB_APP_ID"

// Scheme của intent-filter phải chữ thường và chỉ gồm ký tự hợp lệ (lint AppLinkUrlError).
// App ID thật là chuỗi số; khi chưa cấu hình (placeholder, ví dụ trên CI) dùng scheme cố định.
val facebookLoginScheme = if (facebookAppId.isNotEmpty() && facebookAppId.all { it.isDigit() }) {
    "fb$facebookAppId"
} else {
    "fbplaceholder"
}

val facebookClientToken = System.getenv("FACEBOOK_CLIENT_TOKEN")
    ?: localProperties.getProperty("FACEBOOK_CLIENT_TOKEN")
    ?: "SOCIAL_AGENT_CLIENT_TOKEN"

// LinkedIn Standard OAuth 2.0 qua BFF (docs/research-social-api.md, Luồng B).
// Chỉ có định danh CÔNG KHAI: Client ID của App Member và địa chỉ HTTPS của BFF.
// KHÔNG có client_secret trong app: secret chỉ nằm ở biến môi trường của BFF (backend/linkedin-bff).
val linkedinMemberClientId = System.getenv("LINKEDIN_MEMBER_CLIENT_ID")
    ?: localProperties.getProperty("LINKEDIN_MEMBER_CLIENT_ID")
    ?: "SOCIAL_AGENT_LINKEDIN_MEMBER_CLIENT_ID"

val linkedinBffUrl = System.getenv("LINKEDIN_BFF_URL")
    ?: localProperties.getProperty("LINKEDIN_BFF_URL")
    ?: "SOCIAL_AGENT_LINKEDIN_BFF_URL"

android {
    namespace = "com.lttbdd.socialagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lttbdd.socialagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["facebookAppId"] = facebookAppId
        manifestPlaceholders["facebookClientToken"] = facebookClientToken
        buildConfigField("String", "FACEBOOK_APP_ID", "\"$facebookAppId\"")
        buildConfigField("String", "FACEBOOK_CLIENT_TOKEN", "\"$facebookClientToken\"")
        buildConfigField("String", "LINKEDIN_MEMBER_CLIENT_ID", "\"$linkedinMemberClientId\"")
        buildConfigField("String", "LINKEDIN_BFF_URL", "\"$linkedinBffUrl\"")

        resValue("string", "facebook_app_id", facebookAppId)
        resValue("string", "facebook_client_token", facebookClientToken)
        resValue("string", "fb_login_protocol_scheme", facebookLoginScheme)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    androidResources {
        // Sinh locales_config để người dùng đổi VI/EN trong Settings hệ thống (Android 13+).
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.facebook.login)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
