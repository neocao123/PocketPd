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
        mavenLocal() // self-built pd-core 1.4.4-SNAPSHOT (pd 0.57.0test1, see MIGRATION_057.md W0.1)
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // usb-serial-for-android
    }
}

rootProject.name = "PocketPd"
include(":app")
