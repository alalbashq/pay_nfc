pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ghallah-payment"
include(":core", ":pos", ":pay")
project(":core").projectDir = file("packages/payment-core")
project(":pos").projectDir = file("apps/ghallah-pos/android")
project(":pay").projectDir = file("apps/ghallah-pay/android")
include(":capacitor-android")
project(":capacitor-android").projectDir = file("node_modules/@capacitor/android/capacitor")
