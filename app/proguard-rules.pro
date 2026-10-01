# Optimization & Minification Rules for DocStudio

-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# Preserve App Models & Presentation
-keep class com.example.offlinedocumentcomposer.** { *; }
-keepclassmembers class com.example.offlinedocumentcomposer.** { *; }

# OpenCV Native & Java Bindings
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }
-dontwarn org.opencv.**

# PDFBox Android & FontBox
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn org.bouncycastle.**

# Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Android Framework
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# AndroidX / Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**
