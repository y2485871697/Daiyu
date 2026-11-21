pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io") } }
            filter { includeGroup("com.github.re-ovo.bettertext"); includeModule("com.github.rikkahub", "markdown") }
        }
    }
}
rootProject.name = "代鱼"
include(":app", ":core-domain")
