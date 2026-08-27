plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dashieapp.Dashie.microfrontend"
    compileSdk = 36

    defaultConfig {
        minSdk = 23

        externalNativeBuild {
            cmake {
                // Enable flexible page sizes for Android 15+ compatibility
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }

        ndk {
            // armeabi-v7a added 2026-07: the frontend is FIXED_POINT=16 integer math
            // (same code ESPHome runs on 32-bit ESP32s) — nothing here needs 64-bit.
            // Enables MWW / the dual gate on 32-bit devices (Echo Show 5 Lineage, Fire TV).
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}
