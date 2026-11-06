pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files(providers.gradleProperty("catalogPath").orElse("../../../../../gradle/libs.versions.toml"))) } }
}
rootProject.name = "localica-lint-consumer"
