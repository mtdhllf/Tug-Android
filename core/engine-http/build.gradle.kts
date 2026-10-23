plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
    `signing`
}

group = "io.github.mtdhllf"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
    explicitApiWarning()
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(project(":core:engine-core"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

val sonatypeUsername = providers.environmentVariable("SONATYPE_USERNAME").orNull
val sonatypePassword = providers.environmentVariable("SONATYPE_PASSWORD").orNull
val gpgKey = providers.environmentVariable("GPG_PRIVATE_KEY").orNull
val gpgPassphrase = providers.environmentVariable("GPG_PASSPHRASE").orNull

signing {
    if (!gpgKey.isNullOrBlank()) {
        useInMemoryPgpKeys(gpgKey, gpgPassphrase)
        sign(publishing.publications)
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            artifactId = "tug-http"
            pom {
                name.set("Tug HTTP")
                description.set("Tug multi-connection HTTP download engine (JDK transport)")
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

