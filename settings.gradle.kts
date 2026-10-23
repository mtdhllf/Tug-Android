pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Tug"

include(":app")
include(":core:engine-core")
include(":core:engine-http")
include(":core:engine-okhttp")
include(":core:store-sqlite")
include(":core:engine-torrent")
include(":samples:jvm-downloader")


include(":sample-http")
include(":sample-torrent")
