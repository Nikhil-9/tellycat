plugins {
    id("com.android.application")
}

android {
    namespace = "com.nikhil.glowhome"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nikhil.glowhome"
        minSdk = 26
        targetSdk = 30
        versionCode = 3
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "mp4"   // the background video streams straight out of the APK
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
