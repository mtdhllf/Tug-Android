plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    `signing`
}

group = "io.github.mtdhllf"
version = "1.0.0"

android {
    namespace = "com.github.mtdhllf.tug.core.engine.torrent"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        jvmToolchain(17)
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":core:engine-core"))
    implementation(libs.kotlinx.coroutines.core)
    // libtorrent4j 2.1.0-39（MIT，Android 原生库）
    implementation(libs.libtorrent4j)
    // 默认包含常见 ABI；使用方可按需通过 abiFilters 裁剪体积
    implementation(libs.libtorrent4j.arm)
    implementation(libs.libtorrent4j.arm64)
    implementation(libs.libtorrent4j.x86)
    implementation(libs.libtorrent4j.x8664)
}

val androidJavadocJar = tasks.register<Jar>("androidJavadocJar") {
    archiveClassifier.set("javadoc")
}

val sonatypeUsername = providers.environmentVariable("SONATYPE_USERNAME").orNull
val sonatypePassword = providers.environmentVariable("SONATYPE_PASSWORD").orNull
val gpgKey = providers.environmentVariable("GPG_PRIVATE_KEY").orNull
val gpgPassphrase = providers.environmentVariable("GPG_PASSPHRASE").orNull

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "tug-engine-torrent"
                artifact(androidJavadocJar)
                pom {
                    name.set("Tug Engine Torrent")
                    description.set("Tug BitTorrent/magnet engine (libtorrent4j, download only)")
                    url.set("https://github.com/mtdhllf/Tug-Android")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                    developers {
                        developer {
                            id.set("mtdhllf")
                            name.set("mtdhllf")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/mtdhllf/Tug-Android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/mtdhllf/Tug-Android.git")
                        url.set("https://github.com/mtdhllf/Tug-Android")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "local"
                url = uri(rootProject.layout.buildDirectory.dir("maven-local"))
            }
            if (!sonatypeUsername.isNullOrBlank() && !sonatypePassword.isNullOrBlank()) {
                maven {
                    name = "central"
                    url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                    credentials {
                        username = sonatypeUsername
                        password = sonatypePassword
                    }
                }
            }
        }
    }
    signing {
        if (!gpgKey.isNullOrBlank()) {
            useInMemoryPgpKeys(gpgKey, gpgPassphrase)
            sign(publishing.publications)
        }
    }
}

