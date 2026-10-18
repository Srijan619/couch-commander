pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Football fixtures and live scores, shared with focus-launcher.
includeBuild("../football-core")
// Films and series from TMDB (garbled-store proxy), shared with garbled.
includeBuild("../media-core")

rootProject.name = "couch-commander"
include(":app")
