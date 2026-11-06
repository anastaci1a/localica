plugins {
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(17) }
val embedded by configurations.creating
configurations.implementation { extendsFrom(embedded) }
dependencies {
    embedded(libs.snakeyaml)
    compileOnly(libs.lint.api)
    testImplementation(libs.lint.tests)
    testImplementation(libs.lint.api)
    testImplementation(libs.junit)
}
tasks.test {
    maxHeapSize = "1100m"
    maxParallelForks = 1
    systemProperty("localica.registry.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    dependsOn(tasks.jar)
}

// lintChecks loads one self-contained tooling jar; no parser dependency enters an APK.
tasks.jar {
    from({ embedded.map { zipTree(it) } }) { exclude("META-INF/**", "module-info.class") }
}
