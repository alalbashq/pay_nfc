plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.ghallah.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}
dependencies { testImplementation("junit:junit:4.13.2") }
