plugins {
    `java-library`
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    compileOnly("com.android.tools.lint:lint-api:31.13.2")
    testImplementation("com.android.tools.lint:lint-api:31.13.2")
    testImplementation("com.android.tools.lint:lint-tests:31.13.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.jar { manifest { attributes("Lint-Registry-v2" to "io.github.anastaci1a.localica.lint.ThreadingRegistry") } }
