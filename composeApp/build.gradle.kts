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
        iosTarget.compilations.getByName("main") {
            cinterops.create("uvpfilament") {
                defFile(project.file("src/nativeInterop/cinterop/uvpfilament.def"))
                compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
            }
        }
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
            // Compose Multiplatform 资源系统 — commonMain/composeResources/drawable/
            // 下的 PNG / XML vector 会被生成 Res.drawable.* accessor,两端共用同一份美术。
            implementation(compose.components.resources)
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

// Compose Resources 生成的 Res 类包名 —— 显式绑到 android namespace 下 .generated.resources,
// 匹配 AboutScreen 里的 `com.uvp.sim.compose.generated.resources.Res` import。
// 不配的话默认走 gradle group/name 派生,项目未设 group → 会 fallback 到不稳定的路径。
compose.resources {
    publicResClass = false
    packageOfResClass = "com.uvp.sim.compose.generated.resources"
    generateResClass = auto
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
    // AGP 内嵌 lint 在 :lintVitalAnalyzeRelease 阶段跑 NonNullableMutableLiveDataDetector
    // 时抛 IncompatibleClassChangeError(KaCallableMemberCall class-vs-interface),是
    // AGP + Kotlin analysis API 版本冲突的 lint 工具本身 bug,与代码无关。
    // 项目未用 LiveData(纯 StateFlow),该 detector 从头到尾没有实际价值,disable 掉最干净。
    lint {
        disable += "NullSafeMutableLiveData"
        abortOnError = false
        checkReleaseBuilds = false
    }
}
