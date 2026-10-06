pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "BiliListen"
include(":app", ":shared")
// Historical UI references are local-only and not needed to build the application.
if (file("ui-preview").isDirectory) include(":ui-preview")
