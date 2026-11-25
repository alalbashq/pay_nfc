plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.ghallah.pay"
    compileSdk = 35
    sourceSets["main"].java.srcDir(rootProject.file("packages/capacitor-bridge"))
    sourceSets["main"].assets.srcDir(rootProject.layout.buildDirectory.dir("web/pay"))
    defaultConfig {
        applicationId = "com.ghallah.pay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":capacitor-android"))
    implementation("androidx.appcompat:appcompat:1.6.1")
}
tasks.named("preBuild") { dependsOn(rootProject.tasks.named("buildPayWeb")) }
