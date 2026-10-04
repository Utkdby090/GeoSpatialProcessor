# 1. Keep the Main Entry Point
-keepclasseswithmembers public class * {
    public static void main(java.lang.String[]);
}

# --- SHIELD 0: REFLECTION & KOTLIN METADATA (CRITICAL FOR ORMS) ---
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class kotlin.Metadata { *; }

# --- SHIELD 1: NATIVE UI ENGINE (Skia & Compose) ---
-keep class org.jetbrains.skiko.** { *; }
-keepclassmembers class org.jetbrains.skiko.** { *; }
-keep class org.jetbrains.skia.** { *; }
-keepclassmembers class org.jetbrains.skia.** { *; }
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# --- SHIELD 1b: MAP VIEW (MapCompose, kotlinx-io) ---
-keep class ovh.plrapps.mapcompose.** { *; }
-dontwarn kotlinx.io.**

# --- SHIELD 2: CONCURRENCY (Coroutines) ---
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# --- SHIELD 3: PDF & CRYPTOGRAPHY (OpenPDF/BouncyCastle) ---
-keep class org.bouncycastle.** { *; }
-keep class com.lowagie.** { *; }

# --- SHIELD 4: DATABASE & ORM (Exposed & SQLCipher) ---
-keep class org.sqlite.** { *; }
-keepclassmembers class org.sqlite.** { *; }
-keep class org.jetbrains.exposed.** { *; }
-keepclassmembers class org.jetbrains.exposed.** { *; }

# --- SHIELD 5: YOUR DATA MODELS ---
-keep class com.geospatial.processing.domain.model.** { *; }
-keep class com.geospatial.processing.data.entity.** { *; }
-keep class com.geospatial.processing.data.table.** { *; }

# --- SHIELD 6: LOGGING ---
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }
-assumenosideeffects interface org.slf4j.Logger {
    public void debug(...);
    public void trace(...);
}

# Force ProGuard to ignore warnings from 3rd party optional libraries
-ignorewarnings
-dontwarn org.bouncycastle.**

# --- SHIELD 7: KOTLINX SERIALIZATION (project.json, asset properties JSON) ---
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
-dontnote kotlinx.serialization.**

# --- EXIF/GPS reading (metadata-extractor and its XMP dependency) ---
-keep class com.drew.** { *; }
-keep class com.adobe.xmp.** { *; }
-dontwarn com.drew.**
-dontwarn com.adobe.xmp.**

# DJI Thermal SDK via JNA: native calls and struct fields are bound by reflection.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class com.geospatial.processing.domain.thermal.Dirp* { *; }
-dontwarn com.sun.jna.**
