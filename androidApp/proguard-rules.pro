# ProGuard/R8 rules — release 冷启动优化 (isMinifyEnabled = true)
#
# 项目栈: KMP + Compose Multiplatform + Ktor + kotlinx.serialization
# + Filament 3D + Media3 + androidx.core:core-splashscreen + xmlutil.
#
# 目标: 允许 R8 shrink / obfuscate / optimize 减少 dex 类数量, 冷启动 dex load 显著变快.
# 风险: 反射 / KMP expect-actual / Serialization / native binding 都需 keep 规则, 配错 release 会崩.
# 策略: 只 keep "反射 / 序列化 / native 交互 / KMP 元数据" 相关, 业务代码全部允许混淆.

# ==== Kotlin 元数据(KMP expect-actual 需要) ====
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# ==== kotlinx.serialization(SimConfig / SnapshotUploadEngine 等大量 @Serializable) ====
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-keep,includedescriptorclasses class kotlinx.serialization.internal.** { *; }
-keepclasseswithmembernames class kotlinx.serialization.**Kt { *; }
-keep class kotlinx.serialization.KSerializer
-keepclassmembers class kotlinx.serialization.KSerializer { *; }
-keep,allowobfuscation @kotlinx.serialization.Serializable class com.uvp.sim.** { *; }
-keep,allowobfuscation @kotlinx.serialization.Serializable class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ==== Ktor / Coroutines ====
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# ==== xmlutil (MANSCDP GB28181 XML 序列化) ====
-keep class nl.adaptivity.xmlutil.** { *; }
-keep class io.github.pdvrieze.** { *; }
-keep @nl.adaptivity.xmlutil.serialization.XmlSerialName class ** { *; }
-dontwarn nl.adaptivity.xmlutil.**

# ==== Filament 3D (JNI 反向调 Kotlin 方法必须 keep) ====
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**

# ==== Compose runtime (Recomposer 靠类名找 Composable) ====
-keep class androidx.compose.runtime.** { *; }
-keep,allowoptimization,allowobfuscation @androidx.compose.runtime.Composable class **
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
-dontwarn androidx.compose.**

# ==== KMP 平台桥 ====
-keep class com.uvp.sim.app.PlatformRuntime { *; }
-keep class com.uvp.sim.app.PlatformResources { *; }
-keep class com.uvp.sim.app.PlatformRuntimeAndroid { *; }
-keep class com.uvp.sim.app.PlatformResourcesAndroid { *; }
-keep public class com.uvp.sim.camera.** { public *; }
-keep public class com.uvp.sim.recording.** { public *; }
-keep public class com.uvp.sim.streaming.** { public *; }

# ==== SIP 栈 / GB28181 ====
-keep class com.uvp.sim.sip.** { *; }
-keep class com.uvp.sim.gb28181.** { *; }

# ==== Compose Resources ====
-keep class com.uvp.sim.compose.generated.resources.** { *; }
-keep class org.jetbrains.compose.resources.** { *; }

# ==== MainActivity + ViewModels ====
-keep class com.uvp.sim.MainActivity { *; }
-keep public class * extends androidx.lifecycle.ViewModel { *; }
-keep public class * extends androidx.lifecycle.AndroidViewModel { *; }

# ==== 反射构造 keep ====
-keepclassmembers class ** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ==== Media3 / Camera ====
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ==== 保留 SourceFile 行号(崩栈追踪必须) ====
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==== 稳字优先: 先不 obfuscate, 让 shrink+optimize 出效果, 混淆后续再开 ====
-dontobfuscate
