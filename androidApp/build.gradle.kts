import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val keystoreProps = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.gradle/uvp-sim-keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { keystoreProps.getProperty(it)?.isNotBlank() == true }

android {
    namespace = "com.uvp.sim"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.uvp.gb28181sim"
        minSdk = 26
        targetSdk = 36
        versionCode = 10001
        versionName = "1.0.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val rawPath = keystoreProps.getProperty("storeFile")
                val resolvedPath = rawPath
                    .replace("\$HOME", System.getProperty("user.home"))
                    .replace("~/", System.getProperty("user.home") + "/")
                storeFile = file(resolvedPath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "uvp-gb28181-sim-${defaultConfig.versionName}.apk"
        }
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
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    // 7.5 抓拍 HTTP 上传客户端(SipViewModel.attachSnapshotPipeline 用)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
}
