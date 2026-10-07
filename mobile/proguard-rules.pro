# R8 rules for the DiPlay-H6 Haval H6 build.
#
# The build target is Android 8.1 on a Renesas head unit, so a shrinking regression shows up as a
# crash on the car rather than in CI. This file is therefore deliberately generous: it keeps the
# handful of things R8 genuinely cannot see, and lets everything else be renamed.
#
# The one rule that is not optional is the JNI block. shared/src/main/jni binds by name
# (Java_com_shilapi_xcertplay_media_NativeOpus_create and friends). Renaming the class or the
# method turns those into UnsatisfiedLinkError at the first audio frame.

# --- JNI: name-based binding, so class and native method names must survive verbatim ---
-keepclasseswithmembernames,includedescriptorclasses class com.shilapi.xcertplay.media.NativeOpus {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class com.shilapi.xcertplay.transport.LinuxI2cNative {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class com.shilapi.xcertplay.network.LegacyHotspotNative {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class com.shilapi.xcertplay.transport.UsbHostIoctl {
    native <methods>;
}
# Belt and braces: any future JNI class in these packages is kept automatically.
-keepclasseswithmembernames,includedescriptorclasses class com.shilapi.xcertplay.** {
    native <methods>;
}

# --- Desay SV AIDL, bound against the vendor service at runtime ---
-keep class com.desay.libsvcarinfo.** { *; }
-keep interface com.desay.libsvcarinfo.** { *; }

# --- Parcelable round-trips through Bundles and Intents ---
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# --- Framework classes reached by reflection ---
# BluetoothDevice.createRfcommSocket(int) and BluetoothDevice.isConnected() are hidden APIs
# looked up by name on API 27; they belong to the platform, so no keep rule is needed for them,
# but the constants they are called with live here.
-keepclassmembers class com.shilapi.xcertplay.** {
    @androidx.annotation.Keep *;
}

# --- Kotlin metadata used by nothing at runtime, but harmless and cheap to retain ---
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable

# Bouncy Castle and the bundled Opus build log optional references that R8 cannot resolve.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn javax.naming.**