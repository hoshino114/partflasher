# Keep JNI entry point names for the native image hasher.
-keepclasseswithmembernames class com.partflasher.app.core.util.NativeSha256 {
    native <methods>;
}
