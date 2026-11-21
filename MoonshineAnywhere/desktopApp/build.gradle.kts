plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    testImplementation(libs.kotlin.test)
}

// The streaming runtime for the desktop JVM (native/build-desktop-runtime.sh) travels in the jar; without it the
// app still starts and says what is missing.
val nativeOut = rootProject.layout.projectDirectory.dir("native/out")
tasks.processResources {
    from(nativeOut) { into("native") }
}

// -Ppacks=<dir> points the app at packs somewhere else
val packs = providers.gradleProperty("packs").getOrElse(rootProject.layout.projectDirectory.dir("build/cartridges").asFile.absolutePath)

compose.desktop {
    application {
        mainClass = "sk.ainet.examples.moonshine.MainKt"
        jvmArgs += "-Dmoonshine.packs=$packs"
        nativeDistributions {
            packageName = "MoonshineAnywhere"
            packageVersion = "1.0.0"
        }
    }
}

// Headless: one utterance through the cartridge, transcript and timings on stdout.
//   ./gradlew :desktopApp:transcribe --args="cpu"            the bundled sample on the CPU pack
//   ./gradlew :desktopApp:transcribe --args="gpu my.wav 5"   a 16 kHz mono WAV on Vulkan, five runs
tasks.register<JavaExec>("transcribe") {
    group = "application"
    description = "Transcribes a WAV (or the bundled sample) with the Moonshine cartridge on cpu or gpu"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("sk.ainet.examples.moonshine.TranscribeCliKt")
    systemProperty("moonshine.packs", packs)
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    workingDir = rootProject.projectDir
}

tasks.test {
    systemProperty("moonshine.packs", packs)
}
