-keepattributes *Annotation*, Signature, Exceptions
-keepattributes SourceFile,LineNumberTable

-dontwarn kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(kotlin.reflect.KClass);
}

-keep class * extends java.lang.Enum {
    *;
}

-keepclassmembers class com.neoremote.android.core.model.** {
    *** Companion;
    *** serializer(...);
}

-keep class com.neoremote.android.core.receiver.MobileControlAccessibilityService { *; }
-keep class com.neoremote.android.ui.NeoRemoteApp { *; }

-dontwarn android.view.**
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
