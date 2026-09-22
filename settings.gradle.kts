pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ReaderVoice-Mobile"

include(":parser")
include(":parser-core")
include(":semantic-core")
include(":data-room")
include(":scheduler")
include(":app-android")
include(":voicedesign-app")

