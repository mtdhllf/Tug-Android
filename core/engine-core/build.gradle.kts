
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
    // 包含 TaskManager 等实现（原 store-api 合并），公开面暂时用 warning
    explicitApiWarning()
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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
            artifactId = "tug-engine-core"
            pom {
                name.set("Tug Engine Core")
                description.set("Tug download engine contracts, shared models and task orchestration")
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

