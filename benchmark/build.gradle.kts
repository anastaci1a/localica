plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "io.github.anastaci1a.localica.benchmark"
    compileSdk = 36
    defaultConfig {
        minSdk = 35
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("release") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        create("distribution") {
            initWith(getByName("release"))
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "distribution"
        }
    }
    sourceSets.getByName("main").assets.srcDir("../tools/performance/macrobenchmark")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
baselineProfile { useConnectedDevices = true }

dependencies {
    implementation(libs.androidx.macrobenchmark)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.uiautomator)
}
