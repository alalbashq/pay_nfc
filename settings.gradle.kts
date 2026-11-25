pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ghallah-payment"
include(":core", ":pos", ":pay")
project(":core").projectDir = file("packages/payment-core")
project(":pos").projectDir = file("apps/ghallah-pos/android")
project(":pay").projectDir = file("apps/ghallah-pay/android")
