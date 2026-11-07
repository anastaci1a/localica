plugins {
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
val scenario = providers.gradleProperty("scenario").get()
require(scenario in setOf("positive", "negative"))
layout.buildDirectory.set(file(providers.gradleProperty("smokeOutput").get()))
kotlin.sourceSets.main { kotlin.srcDirs("src/common", "src/$scenario") }
dependencies { lintChecks(files(providers.gradleProperty("registryJar").get())) }
lint {
    checkOnly += "RawUiMotion"
    textReport = true
    xmlReport = true
    abortOnError = true
}
