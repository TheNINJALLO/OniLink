pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://repo.opencollab.dev/main/")
        mavenCentral()
    }
}

rootProject.name = "onibridge-geyser"
