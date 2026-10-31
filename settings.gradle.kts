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

rootProject.name = "Zyke Message"
include(":app")
include(":domain")

// Wear OS watch app. Off by default so the normal build never needs the Wear libraries; build it with
//   ./gradlew -PwithWear=true :wear:assembleDebug :app:assembleDebug
if (providers.gradleProperty("withWear").orNull == "true") include(":wear")
