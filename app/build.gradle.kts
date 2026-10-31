import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        versionCode = 79
        versionName = "0.30.0"
        testInstrumentationRunner = "io.github.anastaci1a.localica.NavigationRandomOwnedSession"
        testProguardFiles("proguard-test-rules.pro")
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
        // Optimized, non-debuggable build that updates the installed debug package in place:
        // same application ID suffix and development signing key as `debug`.
        create("distribution") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = false
            matchingFallbacks += "release"
        }
    }
    // CI selects the minified build for R8 runtime audits: -PlocalicaTestBuildType=distribution.
    testBuildType = providers.gradleProperty("localicaTestBuildType").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Platform-free helpers are test-only, shared by host contracts and device callers.
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.jaudiotagger)
    implementation(libs.androidx.core)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material)
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
// The test manifest registers the Compose test activity; add it to a non-debug test target too.
providers.gradleProperty("localicaTestBuildType").orNull?.takeIf { it != "debug" }?.let {
    dependencies.add("${it}Implementation", libs.compose.test.manifest)
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

// Standalone test components cannot use the target APK's classloader. AGP subtracts
// shared module artifacts; retain the exact resolved stdlib as a runtime file (#488).
val standaloneStdlib = providers.provider {
    configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.single {
        it.moduleVersion.id.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib"
    }
}
val standaloneRuntimeDirectory = layout.buildDirectory.dir("generated/standalone-test-runtime")
val prepareStandaloneTestRuntime = tasks.register<Copy>("prepareStandaloneTestRuntime") {
    // Capture value providers in the action, not the build script or resolved artifacts.
    val runtimeJar = standaloneStdlib.map { it.file }
    val runtimeCoordinate = standaloneStdlib.map { it.moduleVersion.id.toString() }
    val runtimeDirectory = standaloneRuntimeDirectory
    from(runtimeJar)
    into(runtimeDirectory)
    rename { "kotlin-stdlib-standalone.jar" }
    inputs.property("resolvedCoordinate", runtimeCoordinate)
    outputs.file(runtimeDirectory.map { it.file("binding.txt") })
    doLast {
        val original = runtimeJar.get()
        val copied = runtimeDirectory.get().file("kotlin-stdlib-standalone.jar").asFile
        fun digest(file: java.io.File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val originalHash = digest(original)
        check(digest(copied) == originalHash) { "Standalone stdlib bytes differ from target runtime" }
        runtimeDirectory.get().file("binding.txt").asFile.writeText(
            "coordinate=${runtimeCoordinate.get()}\nsha256=$originalHash\n"
        )
    }
}
dependencies {
    androidTestRuntimeOnly(files(standaloneRuntimeDirectory.map {
        it.file("kotlin-stdlib-standalone.jar")
    }).builtBy(prepareStandaloneTestRuntime))
}
