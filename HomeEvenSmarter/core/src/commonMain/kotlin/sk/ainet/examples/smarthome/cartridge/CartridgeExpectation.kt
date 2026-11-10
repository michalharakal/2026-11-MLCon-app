package sk.ainet.examples.smarthome.cartridge

/**
 * What a pipeline stage requires of the cartridge that runs it, written as code next to the pipeline. A pack on
 * disk either conforms or is refused with the list of differences; nothing is picked just because it is there.
 */
data class CartridgeExpectation(
    val task: String,
    val family: String,
    /** Accepted versions, exact strings. */
    val versions: Set<String>,
    val ioMode: String,
    val language: String? = null,
    val sampleRateHz: Int? = null,
    val chunkMs: Int? = null,
    /** Required descriptor attributes, e.g. the tool-call format the NLU parser understands. */
    val attributes: Map<String, String> = emptyMap(),
    val runtime: String = "iree",
    val runtimeVersion: String? = null,
) {
    /** Every way [d] differs from this expectation; empty means it conforms. */
    fun problems(d: CartridgeDescriptor): List<String> = buildList {
        if (d.task != task) add("task ${d.task} != $task")
        if (d.family != family) add("family ${d.family} != $family")
        if (d.version !in versions) add("version ${d.version} not in ${versions.joinToString()}")
        if (d.io?.mode != ioMode) add("io.mode ${d.io?.mode} != $ioMode")
        if (language != null && language !in d.languages) add("language $language not in ${d.languages}")
        if (sampleRateHz != null && d.io?.input?.sampleRateHz != sampleRateHz) add("sample rate ${d.io?.input?.sampleRateHz} != $sampleRateHz Hz")
        if (chunkMs != null && d.io?.input?.chunkMs != chunkMs) add("chunk ${d.io?.input?.chunkMs} != $chunkMs ms")
        for ((k, v) in attributes) if (d.attribute(k) != v) add("$k ${d.attribute(k)} != $v")
        val hint = d.runtimeHint
        if (hint?.runtime != runtime) add("runtime ${hint?.runtime} != $runtime")
        if (runtimeVersion != null && hint?.version != runtimeVersion) add("runtime version ${hint?.version} != $runtimeVersion")
    }

    fun conforms(d: CartridgeDescriptor): Boolean = problems(d).isEmpty()
}

/** The cartridges the home voice pipeline is written against, and the key their manifests must be signed with. */
object HomeCartridges {
    val Asr = CartridgeExpectation(
        task = CartridgeKinds.TASK_ASR,
        family = "moonshine-v2-streaming",
        versions = setOf("0.1.0"),
        ioMode = "streaming",
        language = "en",
        sampleRateHz = 16_000,
        chunkMs = 80,
        runtimeVersion = "3.11.0",
    )

    val Nlu = CartridgeExpectation(
        task = CartridgeKinds.TASK_NLU,
        family = "functiongemma-270m-tool-calling",
        versions = setOf("0.2.0"),
        ioMode = "batch",
        attributes = mapOf("tool_call_format" to "functiongemma-compact-v1"),
        runtimeVersion = "3.11.0",
    )

    /** The demo signing key; the private half never leaves the build machine and is in no repository. */
    val trustedKeys: List<TrustedKey> = listOf(
        TrustedKey.fromPem(
            "homeevensmarter-dev",
            """
            -----BEGIN PUBLIC KEY-----
            MCowBQYDK2VwAyEAEr+52FVudM6Zg189h6yrGXn4JHBPpSr/H3096QWHiAo=
            -----END PUBLIC KEY-----
            """.trimIndent(),
        ),
    )
}
