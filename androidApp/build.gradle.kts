import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// M-8 (audit §3) — release 签名密钥加载 + fail-fast 校验。
//
// keystore.properties 位于 ~/.gradle/uvp-sim-keystore.properties(不进 git,
// 老板本机配)。配置缺失时不能 silent 回落 debug 签名 — debug key 公开通用,
// 把它当 release 签出 APK 会让所有人都能假冒该 appId 出 patch。
//
// 策略:
//  - 配置完整 → 正常装配 release signingConfig
//  - 完全没配(常态:贡献者本地构建只跑 :assembleDebug)→ 不创建 release signingConfig,
//    但 release buildType 会 fail-fast(见 buildTypes.release 块),保证不会 silent 出
//    debug-signed APK
//  - 配置存在但字段缺/路径错 → 立即抛 GradleException,信息含哪个字段错
val keystoreFile = file(System.getProperty("user.home") + "/.gradle/uvp-sim-keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val requiredKeystoreFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingKeystoreFields = requiredKeystoreFields.filter {
    keystoreProps.getProperty(it)?.isNotBlank() != true
}
val hasReleaseSigning = keystoreFile.exists() && missingKeystoreFields.isEmpty()

// 配置存在但字段不全 → 立即抛(不要等到 release task 才发现)。
if (keystoreFile.exists() && missingKeystoreFields.isNotEmpty()) {
    throw GradleException(
        "M-8: keystore.properties 存在(${keystoreFile.absolutePath})但缺字段: " +
            "$missingKeystoreFields — 补齐后重跑,否则 release 构建会无法签名"
    )
}

android {
    namespace = "com.uvp.sim"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.uvp.gb28181sim"
        minSdk = 26
        targetSdk = 36
        versionCode = 10003
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // R8 冷启动优化: shrink 未使用类 + resource shrinking + AGP 默认优化.
            // proguard-rules.pro 配 kotlinx.serialization / Ktor / Compose /
            // Filament / KMP expect-actual 的 keep 规则. 保留 -dontobfuscate 稳字优先.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // M-8:配置完整 → 用 release signingConfig;否则 release 不挂签名,
            // 真跑 :assembleRelease / :bundleRelease 时 AGP 会因为「未挂签名」直接 fail,
            // 不会 silent 输出 debug-signed release APK。
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // M-8 fail-fast:任何 release 装配 task 在执行前确认签名配置存在。
    // (build.gradle.kts 顶层缺字段的硬抛已在配置阶段截住,这里防的是「完全没配」场景:
    // 老板 / 贡献者本机没准备 keystore.properties 想跑 release,该早失败,不要等 AGP 内部
    // 报「No signing config configured」这种模糊错。)
    //
    // 把 hasReleaseSigning + keystorePath 装捕获到 local val,避免 doFirst 序列化 script
    // object reference 触发 configuration-cache 不兼容。
    val releaseSigningPresent = hasReleaseSigning
    val keystorePath = keystoreFile.absolutePath
    tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }
        .configureEach {
            doFirst {
                if (!releaseSigningPresent) {
                    throw GradleException(
                        "M-8: release 构建被拒 — 找不到 $keystorePath 或字段不全。" +
                            "release APK 必须用真实 release keystore 签,不可回落 debug。" +
                            "联系老板拿 keystore 配置,或仅跑 :androidApp:assembleDebug。"
                    )
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

    // Robolectric 需要在 JVM 上跑 Android API,默认 includeAndroidResources=true
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    // 7.5 抓拍 HTTP 上传客户端(SipViewModel.attachSnapshotPipeline 用)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // ---- 测试金字塔(PR-TEST-1) ----
    // Robolectric 单测(在 JVM 跑 Android API,无需模拟器)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Espresso 真机/模拟器 instrumentation test
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
