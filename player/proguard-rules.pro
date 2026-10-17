# kotlinx.serialization (Protocol WebSocket messages in :shared)
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keep,includedescriptorclasses class tech.shaffersoftworks.remotescreens.shared.**$$serializer { *; }
-keepclassmembers class tech.shaffersoftworks.remotescreens.shared.** {
    *** Companion;
}
-keepclasseswithmembers class tech.shaffersoftworks.remotescreens.shared.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class tech.shaffersoftworks.remotescreens.shared.** { *; }

# Compose Remote: reflective getRemoteContext + restricted document APIs (alpha)
-keep class androidx.compose.remote.player.view.RemoteComposePlayer {
    *** getRemoteContext(...);
    *;
}
-keep class androidx.compose.remote.** { *; }

# osmdroid (interactive map cards)
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ML Kit (face wake): ComponentRegistrars are loaded by reflection. firebase-components:16.1.0
# (pulled by face-detection) only keeps the class, not <init>(), and AGP 9 R8 full mode strips
# no-arg constructors — FaceDetection.getClient() then NPEs on a null zzc.
# https://github.com/googlesamples/mlkit/issues/888
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    void <init>();
}
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar
