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

rootProject.name = "NeoRemoteAndroid"
include(":app")

includeBuild("../AndroidLiquidGlass") {
    dependencySubstitution {
        substitute(module("io.github.kyant0:backdrop")).using(project(":backdrop"))
    }
}
