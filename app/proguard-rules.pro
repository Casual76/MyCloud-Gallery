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

# SMBJ & Mbassy (Missing standard Java classes on Android)
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn net.engio.mbassy.**
-dontwarn com.hierynomus.smbj.**
-dontwarn com.hierynomus.protocol.commons.**
-dontwarn com.hierynomus.mssmb2.**

# Keep classes used by SMBJ/Mbassy if needed (optional but safer for reflection)
-keep class net.engio.mbassy.** { *; }
-keep class com.hierynomus.smbj.** { *; }

# Hilt/Dagger
-keep class dagger.hilt.android.internal.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$ComponentManager { *; }

# Missing standard Java classes referenced by code generation libraries (AutoValue/JavaPoet)
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
