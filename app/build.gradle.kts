plugins {
    id("com.android.application")
}

android {
    namespace = "com.gmoqa.shusher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gmoqa.shusher"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
