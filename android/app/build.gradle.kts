plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ua.planner"
    compileSdk = 34
    defaultConfig {
        applicationId = "ua.planner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        val site = (project.findProperty("siteUrl") as String?) ?: "https://example.com/"
        buildConfigField("String", "SITE_URL", "\"$site\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
