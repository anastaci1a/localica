plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.anastaci1a.localica"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.anastaci1a.localica"
        minSdk = 26
        targetSdk = 36
        versionCode = 73
        versionName = "0.29.1-dev73"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            System.getenv("LOCALICA_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Platform-free helpers are test-only, shared by host contracts and device callers.
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.jaudiotagger)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.inspector)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.test.manifest)
}

// Preserve raw host XML in the existing build-report artifact, including failed tests.
val jvmEvidenceTag = providers.environmentVariable("LOCALICA_TEST_EVIDENCE_TAG")
    .orElse(providers.environmentVariable("GITHUB_RUN_ID")).orElse("local")
val archiveDebugUnitXml = tasks.register<org.gradle.api.tasks.Copy>("archiveDebugUnitXml") {
    from(layout.buildDirectory.dir("test-results/testDebugUnitTest")) { include("*.xml") }
    into(jvmEvidenceTag.flatMap { tag ->
        require(tag.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid JVM evidence tag" }
        layout.buildDirectory.dir("reports/jvm-xml/$tag/testDebugUnitTest")
    })
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name == "testDebugUnitTest") finalizedBy(archiveDebugUnitXml)
}
