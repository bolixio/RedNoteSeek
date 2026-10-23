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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RedNoteSeek"

include(":app")
include(":core:network")
include(":core:storage")
include(":core:web")
include(":design-system")
include(":feature:auth")
include(":feature:search")
include(":feature:dots")
include(":feature:detail")
include(":feature:profile")
include(":feature:settings")
