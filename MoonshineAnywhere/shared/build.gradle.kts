import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything the three apps share: the engine contract, the utterance loop with its measurements, and the Compose
// Multiplatform screen. What differs per platform is one small class each: which packs exist, how the cartridge
// is opened, where the microphone is.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    android {
        namespace = "sk.ainet.examples.moonshine.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        androidResources { enable = true }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            api(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        // The cartridge has Android and JVM variants (and Linux/macOS native), none for iOS yet.
        androidMain.dependencies { api(libs.moonshine.cartridge) }
        jvmMain.dependencies { api(libs.moonshine.cartridge) }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "sk.ainet.examples.moonshine.resources"
}
