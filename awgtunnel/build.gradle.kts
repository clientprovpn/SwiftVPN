// Vendored AmneziaWG tunnel module (fork of wireguard-android's :tunnel).
// Drop-in source replacement for the Maven com.wireguard.android:tunnel AAR:
// same API under org.amnezia.awg.*, plus AmneziaWG obfuscation params
// (Jc/Jmin/Jmax/S1-S4/H1-H4/I1-I5) parsed from .conf [Interface] blocks and
// applied by the amneziawg-go backend it compiles as libwg-go.so.
plugins {
    id("com.android.library")
}

// The amneziawg-go UAPI socket lives under /data/data/<pkg>/cache/amneziawg,
// so the Go library must be compiled with OUR applicationId baked in.
val awgPackageName = "ir.swiftvpn"

android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "LongLogTag"
        disable += "NewApi"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.collection:collection:1.5.0")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
