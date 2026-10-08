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
        versionCode = 3
        versionName = "1.0.2"
    }

    // La firma vive fuera del repo (~/.gradle/gradle.properties). Sin ella, el release sale sin firmar.
    val storeFile = providers.gradleProperty("SHUSHER_STORE_FILE").orNull
    signingConfigs {
        if (storeFile != null) create("release") {
            this.storeFile = file(storeFile)
            storePassword = providers.gradleProperty("SHUSHER_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("SHUSHER_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("SHUSHER_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
