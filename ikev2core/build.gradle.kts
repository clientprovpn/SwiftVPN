plugins {
    id("com.android.library")
}

android {
    namespace = "org.strongswan.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        ndk {
            // arm64 only, matching the rest of the app — and the only ABI we
            // ship a prebuilt OpenSSL libcrypto for.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            ndkBuild {
                arguments("-j" + Runtime.getRuntime().availableProcessors())
            }
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
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
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.arch.core:core-common:2.2.0")
    implementation("androidx.preference:preference:1.2.1")
}
