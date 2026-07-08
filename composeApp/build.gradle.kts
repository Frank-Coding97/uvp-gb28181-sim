plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)

    // 全项目 opt-in Kotlin 2.2.x 新 stable 化的 experimental API
    sourceSets.all {
        languageSettings {
            optIn("kotlin.time.ExperimentalTime")
            optIn("kotlin.experimental.ExperimentalObjCName")
            optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }

    androidTarget {
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            // 让 ComposeApp.framework 里 export shared 项目,iosApp 只 link
            // 一个 KMP framework,避免 Kotlin runtime 被两次 injectToRuntime()
            // 触发 RuntimeAssertFailedPanic (see overnight report F3).
            export(project(":shared"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))  // api 让 shared 符号被 export 到 iosApp
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.ui)
            // 3D rendering — load .glb model
            implementation(libs.filament.android)
            implementation(libs.filament.utils)
            implementation(libs.gltfio.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // v1.3-C: iOS SceneKit 层单测. 默认层级模板生成的 iosSimulatorArm64Test 已挂 kotlin-test,
        // 只需目录存在(在 src/iosSimulatorArm64Test/kotlin/... 下). 不显式建 iosTest sourceSet.
    }
}

android {
    namespace = "com.uvp.sim.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
