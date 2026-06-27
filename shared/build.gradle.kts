plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm()  // 提供 JVM target 方便跑 commonTest 单元测试

    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io)
            implementation(libs.xmlutil.core)
            implementation(libs.xmlutil.serialization)
            implementation(libs.ktor.network)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.video)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.security.crypto)
            implementation(libs.ktor.client.cio)
        }
    }
}

android {
    namespace = "com.uvp.sim.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test> {
    maxHeapSize = "1536m"
}

/**
 * OSD 字体 atlas baking — 运行期资产生成。
 *
 * 输入:tools/font-baker/charset/charset-ascii.txt
 * 输出:shared/src/androidMain/assets/osd-font-atlas.png + .json
 *
 * 默认走 ASCII charset + JDK 逻辑字体 Monospaced(零依赖,平台自适配)。
 * 中文 atlas 需老板手动放思源黑体到 tools/font-baker/fonts/source-han-sans-sc.otf
 * 后改 charset 重跑。
 *
 * 这个 task 不挂在常规构建链上(产物已 commit),仅老板手动触发用。
 */
val bakeOsdFontAtlas by tasks.registering(JavaExec::class) {
    group = "osd"
    description = "Bake OSD font atlas (PNG + JSON) into androidMain/assets"
    val baker = project(":tools:font-baker")
    dependsOn(baker.tasks.named("classes"))
    classpath = baker.extensions.getByType<SourceSetContainer>().getByName("main").runtimeClasspath
    mainClass.set("com.uvp.sim.tools.fontbaker.MainKt")
    val fontSpec = (project.findProperty("osdFont") as String?) ?: "Monospaced"
    val charsetFile = (project.findProperty("osdCharset") as String?)
        ?: "${baker.projectDir}/charset/charset-ascii.txt"
    val outPng = "${projectDir}/src/androidMain/assets/osd-font-atlas.png"
    val outJson = "${projectDir}/src/androidMain/assets/osd-font-atlas.json"
    args(fontSpec, charsetFile, outPng, outJson)
}
