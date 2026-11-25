plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.ghallah.pay"
    compileSdk = 35
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
dependencies { implementation(project(":core")) }
