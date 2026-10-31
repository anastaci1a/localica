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
# Test code calls Room statics and builder members the app itself never uses
# (NoSuchMethodError Room.databaseBuilder / inMemoryDatabaseBuilder in run 41235326852).
-keep class androidx.room.Room { *; }
-keep class androidx.room.RoomDatabase { *; }
-keep class androidx.room.RoomDatabase$Builder { *; }
# Run 41236404337: full suite hit the same class of failure in androidx.collection
# (IntObjectMapKt) and androidx.media3 (ExoPlayer$Builder, DataSpec, DefaultDataSource$Factory,
# DefaultAudioSink$Builder, DummyTrackOutput). Test-only members of shared libraries are
# subtracted from the test APK, so keep the libraries whole in the audit build.
-keep class androidx.collection.** { *; }
-keep class androidx.media3.** { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.media3.**
