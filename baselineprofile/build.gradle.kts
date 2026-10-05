plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "tv.own.owntv.baselineprofile"
    compileSdk {
        version = release(37)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Same reason as :app — androidx.benchmark's prebuilt .so are already stripped, so the strip
        // step only ever printed "Unable to strip …" without changing a byte.
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }

    defaultConfig {
        // Macrobenchmark needs API 28+ to read the compilation state it drives.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // :app ships one arm APK, so recording runs on an API 33+ arm64 device or emulator
    // (collection needs API 33+, and the arm TV boxes this app targets are older).
    // `mergeIntoMain = true` in :app writes the result to src/main, where the APK picks it up.
    targetProjectPath = ":app"
}

// Run on a single connected device/emulator. Set `useConnectedDevices = false` and add a managed
// device here if this ever needs to run unattended in CI.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
