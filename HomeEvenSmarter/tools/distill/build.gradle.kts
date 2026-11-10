// Host-side knowledge distillation of the home router: phrases generated from the typed tool catalog, a teacher's
// soft labels, a small SKaiNET MLP trained on them, exported for IREE. Runs on the presenter's laptop, never on the phone.
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass = "sk.ainet.examples.smarthome.distill.DistillKt" }

// Full-batch training keeps one tape of activations per step; the Transformer student needs a few GB of heap.
tasks.named<JavaExec>("run") {
    maxHeapSize = providers.gradleProperty("distillHeap").getOrElse("4g")
    jvmArgs("--add-modules", "jdk.incubator.vector") // lets the CPU backend use its SIMD kernels
}

dependencies {
    implementation(project(":core"))
    // the on-device prompt, rendered by the same code the NLU cartridge uses: the teacher sees what the phone sees
    implementation("sk.ainet.cartridge:nlu-functiongemma-270m-iree:0.1.0")
    implementation("sk.ainet.transformers:skainet-transformers-agent:0.64.2") // ToolDefinition in the prompt API
    implementation(libs.skainet.lang.core)
    implementation(libs.skainet.compile.dag)
    implementation(libs.skainet.compile.hlo)
    implementation(libs.skainet.backend.cpu)
    testImplementation(libs.kotlin.test)
}
