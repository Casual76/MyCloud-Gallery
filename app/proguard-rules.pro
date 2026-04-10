# MyCloud Gallery ProGuard Rules

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mycloudgallery.**$$serializer { *; }
-keepclassmembers class com.mycloudgallery.** {
    *** Companion;
}
-keepclasseswithmembers class com.mycloudgallery.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Room entities
-keep class com.mycloudgallery.core.database.entity.** { *; }

# Keep Retrofit interfaces
-keep,allowobfuscation interface com.mycloudgallery.core.network.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
