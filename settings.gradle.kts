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
// Films and series straight from TMDB, shared with garbled.
includeBuild("../media-core")
// IPTV playlists, guides and VOD catalogues, shared with garbled.
includeBuild("../iptv-core")
// The film and series player, shared with garbled.
includeBuild("../player-core")

rootProject.name = "couch-commander"
include(":app")
