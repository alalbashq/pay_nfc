plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.ghallah.pos"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ghallah.pos"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
}
dependencies { implementation(project(":core")) }
