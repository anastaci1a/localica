plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Capture-only opt-in for #208. Ordinary PR/local/full-suite verification stays unchanged.
val previewBranch = "refs/heads/test/208-native-preview-pilot"
val previewEnvironment = System.getenv()
val trustedPreviewDispatch = previewEnvironment["GITHUB_ACTIONS"] == "true" &&
    previewEnvironment["RUNNER_ENVIRONMENT"] == "github-hosted" &&
    previewEnvironment["RUNNER_OS"] == "Linux" &&
    previewEnvironment["GITHUB_REPOSITORY"] == "anastaci1a/localica" &&
    previewEnvironment["GITHUB_REPOSITORY_OWNER"] == "anastaci1a" &&
    previewEnvironment["GITHUB_EVENT_NAME"] == "workflow_dispatch" &&
    previewEnvironment["GITHUB_REF"] == previewBranch &&
    previewEnvironment["GITHUB_WORKFLOW"] == "Android" &&
    previewEnvironment["GITHUB_WORKFLOW_REF"] == "anastaci1a/localica/.github/workflows/android.yml@$previewBranch" &&
    previewEnvironment["GITHUB_SHA"].orEmpty().matches(Regex("[0-9a-f]{40}")) &&
    previewEnvironment["GITHUB_RUN_ID"].orEmpty().matches(Regex("[1-9][0-9]*")) &&
    previewEnvironment["GITHUB_RUN_ATTEMPT"].orEmpty().matches(Regex("[1-9][0-9]*"))

android {
    namespace = "io.github.anastaci1a.localica"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.anastaci1a.localica"
        minSdk = 26
        targetSdk = 36
        versionCode = 53
        versionName = "0.28.1-dev.20261015.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (trustedPreviewDispatch) {
            // This dispatch is a transport pilot, never a replacement for the full native matrix.
            testInstrumentationRunnerArguments["class"] = "io.github.anastaci1a.localica.NativePreviewCaptureTest"
            testInstrumentationRunnerArguments["localicaPreviewCapture"] = "trusted-hosted-pilot-v1"
            testInstrumentationRunnerArguments["localicaPreviewRun"] = previewEnvironment.getValue("GITHUB_RUN_ID")
            testInstrumentationRunnerArguments["localicaPreviewAttempt"] = previewEnvironment.getValue("GITHUB_RUN_ATTEMPT")
            testInstrumentationRunnerArguments["localicaPreviewSha"] = previewEnvironment.getValue("GITHUB_SHA")
        }
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
