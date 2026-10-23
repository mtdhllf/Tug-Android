plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
    `signing`
}

group = "io.github.mtdhllf"
version = "1.0.0"

android {
    namespace = "com.github.mtdhllf.tug.core.store.sqlite"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
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
                artifactId = "tug-store-sqlite"
                artifact(androidJavadocJar)
                pom {
                    name.set("Tug Store SQLite")
                    description.set("Tug Android TaskStore implementation backed by native SQLite")
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

