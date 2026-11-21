package sk.ainet.examples.moonshine

import sk.ainet.cartridge.asr.moonshine.Language
import sk.ainet.cartridge.asr.moonshine.PackDir
import sk.ainet.cartridge.asr.moonshine.StreamingAsrCartridge
import sk.ainet.cartridge.asr.moonshine.StreamingAsrSession
import sk.ainet.transformers.iree.android.IreeMoonshineStream

/**
 * The desktop binding the cartridge does not have yet: its `StreamingAsrCartridge` contract over
 * [IreeMoonshineStream] and one language flavor of a [PackDir]. A line-for-line counterpart of the cartridge's
 * Android `MoonshineAsrCartridge`, and the candidate for its `jvmMain`.
 */
class DesktopMoonshineCartridge(
    val pack: PackDir,
    languageTag: String = "en",
    private val device: String = if (pack.targetsAccelerator) IreeMoonshineStream.VULKAN_DEVICE else IreeMoonshineStream.CPU_DEVICE,
) : StreamingAsrCartridge {
    override val id: String = pack.id
    override val language: String = Language.select(languageTag, pack.languages)
        ?: throw IllegalArgumentException("pack ${pack.id} holds languages ${pack.languages}, none serves '$languageTag'")

    private var stream: IreeMoonshineStream? = null
    private var session: Session? = null

    @Synchronized
    override fun open(): StreamingAsrSession {
        session?.let { return it }
        val files = pack.model(language)
        val s = IreeMoonshineStream(
            device,
            IreeMoonshineStream.Files(
                files.frontendVmfb.toString(), files.encoderVmfb.toString(), files.adapterVmfb.toString(),
                files.prefillVmfb.toString(), files.stepVmfb.toString(),
                requireNotNull(files.paramsIrpa) { "${pack.id} has no artifacts/weights/$language/params.irpa" }.toString(),
                files.vocabBin.toString(), files.decEmbedBin.toString(),
            ),
        )
        stream = s
        return Session(s).also { session = it }
    }

    /** Stage timings of the last finished utterance, from the runtime. */
    fun stats(): Map<String, String> = stream?.stats().orEmpty()

    @Synchronized
    override fun close() {
        session = null
        stream?.close(); stream = null
    }

    private inner class Session(private val s: IreeMoonshineStream) : StreamingAsrSession {
        @Synchronized override fun feed(pcm: FloatArray): String? = s.feedPcm(pcm)
        @Synchronized override fun finish(): String = s.finish().orEmpty()
        @Synchronized override fun reset() = s.reset()
        override fun close() = reset()
    }
}
