# Keep JNI-facing data classes and the native bridge intact.
-keep class com.trackstudio.app.data.native.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
