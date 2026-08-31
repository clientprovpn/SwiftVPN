plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ir.swiftvpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.swiftvpn"
        minSdk = 24
        targetSdk = 36
        versionCode = 73
        versionName = "5.6.0"

        // The engine module declares two flavor dimensions. We do not want
        // flavors in the app, so tell Gradle which engine variant to link.
        missingDimensionStrategy("implementation", "skeleton")
        missingDimensionStrategy("ovpnimpl", "ovpn23")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
    }

    // Optional release signing. Create a keystore, then put these in
    // ~/.gradle/gradle.properties (never commit them):
    //
    //   swiftvpnKeystore=/absolute/path/to/swiftvpn.jks
    //   swiftvpnKeystorePassword=...
    //   swiftvpnKeyAlias=swiftvpn
    //   swiftvpnKeyPassword=...
    //
    // Generate one with:
    //   keytool -genkeypair -v -keystore swiftvpn.jks -alias swiftvpn \
    //           -keyalg RSA -keysize 2048 -validity 10000
    //
    // Without these properties the release build is left unsigned and you can
    // still install the debug APK.
    signingConfigs {
        create("release") {
            val keystorePath: String? by project
            val ksPath = keystorePath
                ?: project.findProperty("swiftvpnKeystore") as String?
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = project.findProperty("swiftvpnKeystorePassword") as String?
                keyAlias = project.findProperty("swiftvpnKeyAlias") as String?
                keyPassword = project.findProperty("swiftvpnKeyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks the dex heavily — Compose/Material pull in a lot that
            // goes unused. With three native runtimes already making the APK
            // large, this is what keeps the release build to a sane size. The
            // keep rules in proguard-rules.pro protect everything reached by
            // reflection or JNI (the engines, gomobile, WireGuard).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the signing config when a keystore was actually found.
            signingConfigs.getByName("release").storeFile?.let {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

        // REQUIRED by the WireGuard library — not optional tidiness.
        //
        // com.wireguard.config.InetEndpoint uses java.time (Instant, Duration)
        // to cache DNS resolution, and Optional/stream appear throughout the
        // library. Those are API 26 APIs; this app ships minSdk 24. Without
        // desugaring the app compiles and installs fine, then throws
        // NoClassDefFoundError on Android 7 the first time a WireGuard endpoint
        // is resolved — a failure at connect time, not at build time.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true

            // The WireGuard AAR ships the wg-quick tooling alongside the
            // userspace backend. Those two are only used by WgQuickBackend,
            // which drives the KERNEL WireGuard module and needs root — this app
            // only ever constructs GoBackend, so they are dead weight.
            excludes += setOf("**/libwg.so", "**/libwg-quick.so")

            // NOTE: do NOT exclude libosslspeedtest.so. It looks unused (it is
            // the in-app speed test we never call), but NativeUtils' static
            // initializer loads it UNCONDITIONALLY for any non-"skeleton"
            // flavor — and the real flavor name is the combined "skeletonOvpn23",
            // so the `.equals("skeleton")` guard never matches. Removing the lib
            // makes NativeUtils fail to class-load, which crashes OpenVPN (and
            // the app at startup). Learned the hard way in v2.3.
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":openvpnengine"))
    implementation(project(":ikev2core"))

    // WireGuard: Apache-2.0, prebuilt libwg-go.so for arm64-v8a, armeabi-v7a,
    // x86 and x86_64. abiFilters above trims this to arm64 only (~3.3 MB).
    // GoBackend declares its own VpnService in the AAR manifest, which merges
    // in automatically — do not redeclare it.
    implementation(project(":awgtunnel"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Xray: built from source in this repo, NOT from Maven — there is no
    // published artifact. libv2ray.aar wraps XTLS/xray-core (MPL-2.0) via
    // AndroidLibXrayLite (LGPL-3.0) using gomobile. It bundles libgojni.so
    // (arm64-v8a, ~35 MB) which includes xray-core's own gvisor TUN stack, so
    // no separate tun2socks native library is needed. See docs/BUILDING_XRAY.md
    // for how to regenerate it.
    implementation(files("libs/libv2ray.aar"))
    // QR: encode (show a profile) and scan (camera capture).
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    // Backdrop blur for the liquid-glass UI (RenderEffect on 12+, graceful
    // translucent fallback below it).
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    debugImplementation(libs.androidx.ui.tooling)
}

