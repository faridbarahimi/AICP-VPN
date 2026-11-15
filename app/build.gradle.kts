import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aicp.vpn"
        minSdk = 29
        targetSdk = 37
        versionCode = 769
        versionName = (project.findProperty("aicpVersion") as? String)?.takeIf { it.isNotBlank() } ?: "2.3.28"

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        // The upstream PattNG x86 build also ships no Aether/Psiphon/Tor binaries;
                        // AetherCoreManager checks native support before launching those profiles.
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DISTRIBUTION", "\"AICP\"")
    }

    signingConfigs {
        create("aicpRelease") {
            val releaseStoreFile = System.getenv("AICP_RELEASE_STORE_FILE")
            if (!releaseStoreFile.isNullOrBlank()) {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("AICP_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("AICP_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("AICP_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (!System.getenv("AICP_RELEASE_STORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("aicpRelease")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI") ?: "universal"
                output.outputFileName = "AICP-VPN_${variant.versionName}_r1_${abi}.apk"
            }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // Treat Jupiter discovery warnings as failures: JUnit 4 failed on an invalid @Test
            // method, such as one that returns a value, while Jupiter only warns and skips it.
            // The key is @API(status = EXPERIMENTAL) in JUnit 6; after a JUnit upgrade, check
            // that a temporary `@Test fun probe() = 1` still fails the unit test task.
            it.systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
        }
    }

}

// Fail early if required native runtimes or the signed Psiphon bootstrap list are omitted.
// Hev JNI is loaded in-process; libhevsockstun is a standalone root-mode executable. The
// Aether/Psiphon/Lyrebird binaries are executable child processes packaged under lib*.so names.
val verifyNativeRuntimeAssets = tasks.register("verifyNativeRuntimeAssets") {
    group = "verification"
    description = "Checks pinned Aether, Psiphon, Lyrebird, Hev and signed Psiphon-list runtime assets."
    doLast {
        val runtimeFiles = mapOf(
            "arm64-v8a" to listOf("libaether.so", "libpsiphon-tunnel-core.so", "liblyrebird.so", "libhev-socks5-tunnel.so", "libhevsockstun.so"),
            "armeabi-v7a" to listOf("libaether.so", "libpsiphon-tunnel-core.so", "liblyrebird.so", "libhev-socks5-tunnel.so", "libhevsockstun.so"),
            "x86_64" to listOf("libaether.so", "libpsiphon-tunnel-core.so", "liblyrebird.so", "libhev-socks5-tunnel.so", "libhevsockstun.so"),
            "x86" to listOf("libhev-socks5-tunnel.so", "libhevsockstun.so"),
        )
        val repoRoot = rootProject.projectDir
        val nativeManifest = repoRoot.resolve("native-runtime-assets.sha256")
        check(nativeManifest.isFile) { "Missing pinned native hash manifest: ${nativeManifest.absolutePath}" }
        val pinnedHashes = linkedMapOf<String, String>()
        nativeManifest.readLines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            check(parts.size == 2) { "Malformed native hash manifest line: $line" }
            check(pinnedHashes.put(parts[1], parts[0]) == null) { "Duplicate native hash entry: ${parts[1]}" }
        }
        val requiredPaths = runtimeFiles.flatMap { (abi, names) ->
            names.map { name -> "app/libs/$abi/$name" }
        }.toSet()
        check(pinnedHashes.keys == requiredPaths) {
            "Native hash manifest paths do not match the required runtime ABI/file matrix."
        }
        fun sha256(path: java.io.File): String = MessageDigest.getInstance("SHA-256")
            .digest(path.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        runtimeFiles.forEach { (abi, names) ->
            names.forEach { name ->
                val binary = file("libs/$abi/$name")
                val relative = "app/libs/$abi/$name"
                check(binary.isFile && binary.length() > 0L) {
                    "Missing native runtime asset: ${binary.relativeTo(projectDir)}. " +
                        "Run scripts/fetch-pinned-native-runtime-assets.sh or build from pinned sources."
                }
                check(binary.canExecute()) {
                    "Native runtime asset must be executable: ${binary.relativeTo(projectDir)}"
                }
                check(sha256(binary) == pinnedHashes[relative]) {
                    "Native runtime SHA-256 mismatch: $relative. Refuse to build with an unpinned binary."
                }
            }
        }
        val list = file("src/main/assets/psiphon_servers.dat")
        check(list.isFile && list.length() > 0L) {
            "Missing signed Psiphon server list: ${list.relativeTo(projectDir)}"
        }
        val stamp = file("src/main/assets/psiphon_servers.stamp")
        check(stamp.isFile && stamp.readText().trim().toLongOrNull()?.let { it > 0L } == true) {
            "Missing or invalid Psiphon server-list publication stamp: ${stamp.relativeTo(projectDir)}"
        }
    }
}
// The AAR is intentionally not committed as a generated binary. Pin its official release and
// source metadata so a stale or substituted core cannot silently enter an APK/AAB.
val verifyXrayCoreArtifact = tasks.register("verifyXrayCoreArtifact") {
    group = "verification"
    description = "Verifies the pinned AndroidLibXrayLite AAR and its source dependency lock."
    doLast {
        val lockFile = rootProject.projectDir.resolve("xray-aar.lock.properties")
        check(lockFile.isFile) { "Missing Xray artifact lock: ${lockFile.absolutePath}" }
        val lock = Properties().apply {
            lockFile.inputStream().use { load(it) }
        }
        fun required(key: String): String = lock.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Missing '$key' in ${lockFile.name}")
        fun sha256(path: java.io.File): String = MessageDigest.getInstance("SHA-256")
            .digest(path.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val aar = file("libs/libv2ray.aar")
        check(aar.isFile && aar.length() > 0L) {
            "Missing libv2ray.aar. Run scripts/fetch-pinned-xray-aar.sh before building."
        }
        check(sha256(aar) == required("aar_sha256")) {
            "libv2ray.aar does not match pinned ${required("release_tag")}; inspect it before replacing."
        }
        val repoRoot = rootProject.projectDir
        val sourceRoot = repoRoot.resolve("AndroidLibXrayLite")
        val sourceManifest = repoRoot.resolve("androidlibxraylite-source.sha256")
        check(sourceManifest.isFile && sha256(sourceManifest) == required("source_manifest_sha256")) {
            "AndroidLibXrayLite source manifest differs from the pinned artifact/source release."
        }
        sourceManifest.readLines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            check(parts.size == 2) { "Malformed AndroidLibXrayLite source manifest line: $line" }
            val sourceFile = repoRoot.resolve(parts[1])
            check(sourceFile.isFile && sha256(sourceFile) == parts[0]) {
                "AndroidLibXrayLite source file differs from the pinned release: ${parts[1]}"
            }
        }
        val goMod = sourceRoot.resolve("go.mod")
        val goSum = sourceRoot.resolve("go.sum")
        check(goMod.isFile && sha256(goMod) == required("go_mod_sha256")) {
            "AndroidLibXrayLite/go.mod differs from the pinned AAR source revision."
        }
        check(goSum.isFile && sha256(goSum) == required("go_sum_sha256")) {
            "AndroidLibXrayLite/go.sum differs from the pinned AAR source revision."
        }
        check(goMod.readLines().any { it.trim() == "replace github.com/xtls/xray-core => ${required("xray_core_replace")}" }) {
            "AndroidLibXrayLite Xray replacement does not match the pinned custom-core source."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyNativeRuntimeAssets, verifyXrayCoreArtifact) }

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR Code: CameraX + ZXing
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Reorderable list
    implementation(libs.reorderable)

    // Testing Libraries
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
