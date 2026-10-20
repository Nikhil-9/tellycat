plugins {
    id("com.android.application")
}

android {
    namespace = "com.nikhil.glowcat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nikhil.glowcat"
        minSdk = 26
        // 30 on purpose: the service registers a runtime receiver without the API 34 export flag, and the
        // behaviour was built and tested against Android 11 TVs. Raise with care.
        targetSdk = 30
        versionCode = 3
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // TellyCat and Glow Home must share one signing key (signature permission). Sign both with the
            // same key; see README. Debug builds from one machine already share the debug key.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // frames are already compressed WebP: store them as-is so each frame is read without inflating
        noCompress += "webp"
    }

    lint {
        // targetSdk 30 is deliberate (see above); don't fail release builds over it
        checkReleaseBuilds = false
        abortOnError = false
    }
}
