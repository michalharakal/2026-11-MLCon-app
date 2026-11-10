// Host-side knowledge distillation of the home router: phrases generated from the typed tool catalog, a teacher's
// soft labels, a small SKaiNET MLP trained on them, exported for IREE. Runs on the presenter's laptop, never on the phone.
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass = "sk.ainet.examples.smarthome.distill.DistillKt" }

dependencies {
    implementation(project(":core"))
    implementation(libs.skainet.lang.core)
    implementation(libs.skainet.compile.dag)
    implementation(libs.skainet.compile.hlo)
    implementation(libs.skainet.backend.cpu)
    testImplementation(libs.kotlin.test)
}
