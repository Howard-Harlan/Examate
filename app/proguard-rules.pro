# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# 保留调试堆栈的行号与源文件名，便于 release 包排查问题
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留泛型签名（Compose / Kotlin 协程等反射使用）
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# ───────── Shizuku / AIDL ─────────
# 项目自己生成的 IUserService（AIDL Stub），UserService 继承自其 Stub
-keep class go.examate.hyp.IUserService { *; }
-keep class go.examate.hyp.IUserService$* { *; }
-keep class go.examate.hyp.service.UserService { *; }
# Shizuku SDK
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ───────── Compose ─────────
# Compose 自带 consumer rules，下列为补充保险
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class go.examate.hyp.ui.** { *; }
# Compose 预览 / Modifier 工厂通过反射调用
-dontwarn androidx.compose.**

# ───────── Backdrop 液态玻璃库 ─────────
-keep class com.kyant.backdrop.** { *; }
-keep class com.kyant.shapes.** { *; }
-dontwarn com.kyant.**

# ───────── Kotlin 元数据 ─────────
-keep class kotlin.Metadata { *; }
-keepattributes KotlinMetadata

# ───────── WebView（保留 JS 交互能力，本项目目前未使用 JS 接口，留作扩展）─────────
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ───────── 支付宝 SDK ─────────
-keep class com.alipay.android.app.** { *; }
-keep class com.alipay.mobile.** { *; }
-keep class com.alipay.sdk.** { *; }
-keep class com.ut.device.** { *; }
-dontwarn com.alipay.**
-dontwarn com.ta.utdid2.**
-dontwarn com.ut.device.**

# ───────── QuickJS wrapper(com.whl.quickjs) ─────────
# QuickJS 通过 JNI 调用 QuickJSContext.evaluate 等;JNI 类名混淆会导致调用失败
-keep class com.whl.quickjs.wrapper.** { *; }
-keep class com.whl.quickjs.android.** { *; }
-dontwarn com.whl.quickjs.**
# 我们传给 QuickJS 的 JSCallFunction 是匿名 Lambda,QuickJS wrapper 通过反射找 invoke 方法
-keepattributes Exceptions
