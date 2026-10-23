plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.github.mtdhllf.tug.sample.MainKt")
}

dependencies {
    implementation(project(":core:engine-core"))
    implementation(project(":core:engine-http"))
    implementation(libs.kotlinx.coroutines.core)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

