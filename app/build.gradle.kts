import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from a gitignored keystore.properties when present.
// F-Droid builds without that file, producing an unsigned APK it verifies against
// the published (signed) binary for reproducible builds.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseSigning) keystorePropertiesFile.inputStream().use { load(it) }
}

// Fork: our per-build counter, bumped by `buildFork` after every successful build and reset to 1
// on every upstream sync — so "+N" always reads as "our Nth build on this upstream base".
val forkBuildNumber = (project.findProperty("BUILD_NUMBER") as String?)?.trim()?.toIntOrNull() ?: 1

// Assigned inside defaultConfig below, consumed by `archivesName` / `buildFork` at the end.
var forkVersionName = ""
var forkVersionCode = 0

android {
    namespace = "com.xnotes"
    compileSdk = 36
    // Pinned toolchain for the vendored tree-sitter build (F-Droid reproducibility).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Fork: installs side-by-side with upstream xnotes. The `com.xnotes` NAMESPACE above is
        // deliberately left alone — only the installed id differs, so rebases stay small.
        applicationId = "shiroikuma.sokki"
        minSdk = 26
        targetSdk = 36
        versionCode = 47
        versionName = "0.8.10"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // --- fork version tail (shiroikuma-sokki) -----------------------------------------
        // Upstream's two version literals above are never hand-edited: a rebase brings the new
        // base in by itself and these lines transform whatever they happen to say.
        //   versionName = "<upstream>+NNN"      e.g. 0.8.10+001
        //   versionCode = <upstream> * 10000 + N  e.g. 470001
        // The multiplier keeps upgrades monotonic across upstream bumps (47 -> 48 lifts every
        // code in the new line above every code in the old one).
        forkVersionCode = versionCode!! * 10000 + forkBuildNumber
        forkVersionName = "$versionName+${forkBuildNumber.toString().padStart(3, '0')}"
        versionCode = forkVersionCode
        versionName = forkVersionName

        // Fork: 白い熊's device is arm64. Upstream ships three ABIs of the vendored tree-sitter
        // libs in one universal APK; we keep only the one that runs here, which cuts the native
        // payload to a third. Upstream's line above stays untouched so it never conflicts.
        ndk {
            abiFilters.clear()
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // The tree-sitter parser tables gzip ~6x: compressed packaging keeps the
            // APK at ~12MB instead of ~30MB, at the cost of extract-on-install.
            useLegacyPackaging = true
        }
    }

    // F-Droid rejects the AGP dependency-metadata block in the APK signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // R8 shrinks/optimises release builds (see proguard-rules.pro for the few keeps). R8
            // output is reproducible (pinned AGP fixes the R8 version + mapping); the one
            // non-reproducible artefact, the baseline profile, is dropped below (see ArtProfile).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// Fork: name the build outputs after the fork rather than upstream's "app".
base {
    archivesName = "shiroikuma-sokki_$forkVersionName"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// F-Droid runs verified builds: its from-source APK must byte-match the released one. AGP's
// auto-merged baseline profile (assets/dexopt/baseline.prof, built from the Compose/AndroidX
// library profiles) is not reproducible across build hosts, so omit it from release builds.
// Tradeoff: slightly slower first-run startup (no ART pre-warm profile).
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

dependencies {
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.androidsvg)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

// Fork: the one build entry point. Assembles the signed release, copies the APK to ~/tmp under the
// family naming convention, and bumps BUILD_NUMBER so the next build takes a fresh, unused +N.
tasks.register("buildFork") {
    group = "build"
    description = "Build the signed release APK, copy it to ~/tmp, and bump BUILD_NUMBER."
    dependsOn("assembleRelease")

    // Configuration-cache-safe: capture every project-derived value HERE, at configuration time.
    // The doLast lambda must not touch `layout` / `rootProject` / any other project service.
    val apkName = "shiroikuma-sokki_${forkVersionName}_arm64-v8a.apk"
    val builtVersionCode = forkVersionCode
    val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")
    val userHome = providers.systemProperty("user.home")
    val propsFile = rootProject.file("gradle.properties")
    val currentBuildNumber = forkBuildNumber

    doLast {
        val outputDir = releaseApkDir.get().asFile
        val targetDir = File(userHome.get(), "tmp")
        targetDir.mkdirs()

        val apk = outputDir.listFiles { _, name -> name.endsWith(".apk") }?.firstOrNull()
            ?: throw GradleException("No APK found in $outputDir")
        val targetFile = File(targetDir, apkName)
        apk.copyTo(targetFile, overwrite = true)
        println("[1;36m>>> ${targetFile.absolutePath}[0m")
        println("[1;36m>>> versionCode $builtVersionCode[0m")

        // Auto-increment BUILD_NUMBER so the next build cannot reuse this one's name.
        val nextBuildNumber = currentBuildNumber + 1
        propsFile.writeText(
            propsFile.readText().replace(
                "BUILD_NUMBER=$currentBuildNumber",
                "BUILD_NUMBER=$nextBuildNumber",
            ),
        )
        println("[1;36m>>> BUILD_NUMBER bumped to $nextBuildNumber[0m")
    }
}
