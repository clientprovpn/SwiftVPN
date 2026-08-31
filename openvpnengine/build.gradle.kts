import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskProvider

/*
 * Upstream: ics-openvpn by Arne Schwabe, GNU GPL v2 with additional terms.
 * See doc/LICENSE.txt in the upstream repository for full terms.
 *
 * This is a modified version of upstream main/build.gradle.kts, adapted to
 * build the OpenVPN engine as an Android LIBRARY module instead of an
 * application. Changes from upstream:
 *   - com.android.application  ->  com.android.library
 *   - removed: splits {}  (invalid in a library module)
 *   - removed: signingConfigs, bundle {}, testBuildType (application concepts)
 *   - removed: the "ui" product flavor and its source set, so none of the
 *     upstream user interface is compiled in. We build "skeleton" only.
 *   - compileSdk pinned to 36 (AGP 8.13.x maximum)
 */

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 36
    // NDK disabled: ships prebuilt .so in src/main/jniLibs (native sources unchanged).

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }


    sourceSets {
        getByName("main") {
            // The native build stages openvpn assets here.
            assets.directories.add("src/main/ovpnassets")
        }
        create("skeleton") {}
    }

    // Upstream uses two dimensions. We keep the same shape so upstream source
    // sets and BuildConfig fields resolve, but only ever build
    // skeleton + ovpn23 (OpenVPN 3 engine, no upstream UI).
    flavorDimensions += listOf("implementation", "ovpnimpl")

    productFlavors {
        create("skeleton") {
            dimension = "implementation"
        }
        create("ovpn23") {
            dimension = "ovpnimpl"
            buildConfigField("boolean", "openvpn3", "true")
        }
    }

    lint {
        // The engine is upstream code; do not gate our build on its lint.
        abortOnError = false
        disable += setOf("MissingTranslation", "UnsafeNativeCodeLocation")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// SWIG generates the Java binding for the OpenVPN 3 C++ client API.
var swigcmd = "swig"
if (file("/opt/homebrew/bin/swig").exists()) swigcmd = "/opt/homebrew/bin/swig"
else if (file("/usr/local/bin/swig").exists()) swigcmd = "/usr/local/bin/swig"

abstract class GenerateSwigTask : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

fun registerGenSwigTask(
    variantName: String,
    variantDirName: String
): TaskProvider<GenerateSwigTask> {
    val baseDir = layout.buildDirectory.dir("generated/source/ovpn3swig/${variantDirName}")

    return tasks.register<GenerateSwigTask>("generateOpenVPN3Swig${variantName}") {
        val genDir = baseDir.get().asFile.resolve("net/openvpn/ovpn3")
        outputDir.set(baseDir)

        doFirst {
            mkdir(genDir)
        }
        commandLine(
            listOf(
                swigcmd,
                "-outdir", genDir.absolutePath,
                "-outcurrentdir",
                "-c++",
                "-java",
                "-package", "net.openvpn.ovpn3",
                "-Isrc/main/cpp/openvpn3/client",
                "-Isrc/main/cpp/openvpn3/",
                "-DOPENVPN_PLATFORM_ANDROID",
                "-o", "${genDir}/ovpncli_wrap.cxx",
                "-oh", "${genDir}/ovpncli_wrap.h",
                "src/main/cpp/openvpn3/client/ovpncli.i"
            )
        )
        inputs.files("src/main/cpp/openvpn3/client/ovpncli.i")
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val execTask = registerGenSwigTask(variant.name, variant.name.replace("-", "/"))
        variant.sources.java?.addGeneratedSourceDirectory(execTask, GenerateSwigTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    // The skeleton source set references AppCompat for its stub activities.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.security.crypto)
}
