# Audit-only (-PlocalicaTestBuildType=distribution); never part of the shipped APK.
# Gradle subtracts libraries shared with the app from the androidTest APK, so test code
# resolves them in the minified app process. Keep those libraries intact; app code,
# Room, Media3, AndroidX and jaudiotagger still go through normal R8 shrinking.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.compose.** { *; }
-dontwarn kotlin.**
# Test code reaches app internals and library classes by their original names and
# members, and the test APK is shrunk without the app's name mapping.
-dontobfuscate
-keep class io.github.anastaci1a.localica.** { *; }
