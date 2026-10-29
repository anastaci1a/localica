plugins { alias(libs.plugins.android.application) }
android {
    namespace = "io.github.anastaci1a.localica"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.anastaci1a.localica"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "preflight-fixture"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { debug { applicationIdSuffix = ".debug" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
