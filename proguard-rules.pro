# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.tvassist.**$$serializer { *; }
-keepclassmembers class com.tvassist.** {
    *** Companion;
}
-keepclasseswithmembers class com.tvassist.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the @Serializable model classes themselves (their fields are accessed via reflection/JNI-free
# serializers, but R8 can otherwise rename/strip members the generated serializer expects).
-keep @kotlinx.serialization.Serializable class com.tvassist.** { *; }

# libVLC talks to native code over JNI; its Java classes/callbacks must not be renamed or stripped.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }

# Media3 (ExoPlayer) and Coil ship their own consumer ProGuard rules inside their AARs, so no
# extra keeps are needed for them here.

# Bouncy Castle (self-signed TLS cert generation) — keep + silence optional-provider warnings.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
