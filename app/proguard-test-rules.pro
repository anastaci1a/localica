# Test-only rules for the minified androidTest APK (-PlocalicaTestBuildType=distribution).
# JUnit and the AndroidX runner discover tests and rules by reflection.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-keep class io.github.anastaci1a.localica.** { *; }
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class kotlin.Metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
