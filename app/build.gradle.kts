import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.github.mtdhllf.tug"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.github.mtdhllf.tug"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "v1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("arm") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
        }
        create("x86") {
            dimension = "abi"
            ndk { abiFilters += "x86" }
        }
        create("x8664") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
        create("universal") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":core:engine-core"))
    implementation(project(":core:engine-http"))
    implementation(project(":core:store-sqlite"))
    implementation(project(":core:engine-torrent"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * 一键生成 v1.0.0 的按 ABI 拆分 APK、全 ABI APK 和 AAB，并写入 SHA256SUMS。
 *
 * 输出：
 * - tug-arm64-v1.0.0.apk
 * - tug-arm-v1.0.0.apk
 * - tug-v1.0.0.apk（全 ABI）
 * - tug-x86-v1.0.0.apk
 * - tug-x86_64-v1.0.0.apk
 * - tug-v1.0.0.aab
 * - SHA256SUMS.txt
 */
val releaseAppVersion = android.defaultConfig.versionName ?: "v1.0.0"
val releaseApkRoot = layout.buildDirectory.dir("outputs/apk").get().asFile
val releaseBundleFile = layout.buildDirectory
    .file("outputs/bundle/universalRelease/app-universal-release.aab").get().asFile
val releaseOutDir = rootProject.layout.projectDirectory.dir("release/v1.0.0").asFile

val packageReleaseApks = tasks.register("packageReleaseApks") {
    group = "build"
    description = "Build ABI split APKs, universal APK and AAB, then copy them to release/v1.0.0."
    notCompatibleWithConfigurationCache("Copies assembled release outputs into release/v1.0.0.")
    dependsOn(
        "assembleArm64Release",
        "assembleArmRelease",
        "assembleX86Release",
        "assembleX8664Release",
        "assembleUniversalRelease",
        "bundleUniversalRelease",
    )

    doLast {
        fun findApk(name: String): File =
            releaseApkRoot.walkTopDown().firstOrNull { it.isFile && it.name == name }
                ?: error("Missing APK output: $name (searched $releaseApkRoot)")

        releaseOutDir.mkdirs()
        val targets = linkedMapOf(
            findApk("app-universal-release.apk") to "tug-$releaseAppVersion.apk",
            findApk("app-arm64-release.apk") to "tug-arm64-$releaseAppVersion.apk",
            findApk("app-arm-release.apk") to "tug-arm-$releaseAppVersion.apk",
            findApk("app-x86-release.apk") to "tug-x86-$releaseAppVersion.apk",
            findApk("app-x8664-release.apk") to "tug-x86_64-$releaseAppVersion.apk",
            releaseBundleFile to "tug-$releaseAppVersion.aab",
        )

        targets.forEach { (source, name) ->
            require(source.isFile) { "Missing build output: ${source.absolutePath}" }
            source.copyTo(releaseOutDir.resolve(name), overwrite = true)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val checksums = targets.values.map { name ->
            digest.reset()
            val hash = digest.digest(releaseOutDir.resolve(name).readBytes())
                .joinToString("") { "%02X".format(it) }
            "$hash  $name"
        }
        releaseOutDir.resolve("SHA256SUMS.txt").writeText(
            checksums.joinToString("\n", postfix = "\n"),
        )
        println("Release artifacts written to ${releaseOutDir.absolutePath}")
    }
}

// 保留原有命令：:app:testDebugUnitTest 会覆盖所有 ABI flavor。
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Run unit tests for every ABI flavor."
    dependsOn(
        "testArm64DebugUnitTest",
        "testArmDebugUnitTest",
        "testX86DebugUnitTest",
        "testX8664DebugUnitTest",
        "testUniversalDebugUnitTest",
    )
}


