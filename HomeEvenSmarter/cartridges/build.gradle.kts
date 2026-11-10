import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The bridge between the app and the two cartridges: locating materialized pack_dirs on the device, downloading them
// from the companion server, and adapting the blueprint modules' Kotlin APIs (Moonshine ASR, FunctionGemma NLU) to
// the engine contracts in :core. The runtime bindings exist on Android; the JVM source set reports them unavailable
// until the desktop stage lands.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    android {
        namespace = "sk.ainet.examples.smarthome.cartridges"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.kotlinx.serializationJson)
            api(libs.kotlinx.ioCore)                 // kotlinx.io.files.Path is part of the public API (CartridgeStore.root)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientCio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.clientMock)
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle.prov)    // Ed25519 manifest signatures
        }
        androidMain.dependencies {
            // Ed25519 manifest signatures: the platform provider has it only from API 33, minSdk is 29.
            implementation(libs.bouncycastle.prov)
            // Resolved to the sibling SKaiNET-cartridge-blueprints checkout by the included build (settings.gradle.kts).
            implementation("sk.ainet.cartridge:asr-moonshine-v2-streaming-iree:0.1.0")
            implementation("sk.ainet.cartridge:nlu-functiongemma-270m-iree:0.1.0")
        }
    }
}
