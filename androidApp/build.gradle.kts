plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.uvp.sim"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.uvp.gb28181sim"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-M1-dev"
    }

    buildFeatures {
        compose = true
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
    implementation(project(":shared"))
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.androidx.camera.view)
}
