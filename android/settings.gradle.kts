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
        // Pleos Vehicle SDK Maven repository (not added yet).
        // See: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup
        // maven { url = uri("<Pleos Vehicle SDK Maven URL>") }
    }
}

rootProject.name = "First30"
include(":core")
include(":app")
