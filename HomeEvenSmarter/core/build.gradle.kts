import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Domain and pipeline: the home model, the tool → command mapping, the voice pipeline state machine and the engine
// contracts the cartridges are adapted to. Pure Kotlin — no Compose, no cartridge dependency — so it runs in JVM
// tests with fake engines and on every platform the UI is built for.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    android {
        namespace = "sk.ainet.examples.smarthome.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest { }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

// The tool catalog is code (HomeTools.catalog); this renders the JSON the NLU cartridge is materialized with.
// scripts/materialize.sh runs it first, so a pack is never built from a stale catalog.
tasks.register<JavaExec>("writeToolCatalog") {
    group = "cartridges"
    description = "Generate cartridges/catalogs/home-tools.v1.json from HomeTools.catalog"
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    classpath(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("sk.ainet.examples.smarthome.tools.WriteToolCatalogKt")
    args(rootProject.file("cartridges/catalogs/home-tools.v1.json").absolutePath)
}

// CatalogContractTest reads the generated catalog: without this, an edited JSON leaves jvmTest UP-TO-DATE.
tasks.named<Test>("jvmTest") {
    inputs.file(rootProject.file("cartridges/catalogs/home-tools.v1.json")).withPropertyName("toolCatalog")
}
