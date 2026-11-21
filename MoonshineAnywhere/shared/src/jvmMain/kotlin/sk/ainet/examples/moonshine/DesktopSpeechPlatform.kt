package sk.ainet.examples.moonshine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import sk.ainet.cartridge.asr.moonshine.PackDir
import sk.ainet.cartridge.asr.moonshine.StreamingAsrCartridge
import sk.ainet.transformers.iree.android.IreeMoonshineStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * The desktop JVM: packs from a directory (`-Dmoonshine.packs`, `MOONSHINE_PACKS`, else `build/cartridges`), CPU
 * through IREE's local-task driver and GPU through Vulkan, the microphone through javax.sound.
 */
class DesktopSpeechPlatform(
    private val packs: File = File(System.getProperty("moonshine.packs") ?: System.getenv("MOONSHINE_PACKS") ?: "build/cartridges"),
) : SpeechPlatform {
    override val name: String =
        "Desktop JVM · ${System.getProperty("os.name")} ${System.getProperty("os.arch")} · JDK ${Runtime.version().feature()}"

    override fun engines(): List<EngineOption> = listOf(
        option(Device.CPU, "IREE · local-task", "cpu"),
        option(Device.GPU, "IREE · Vulkan", "vulkan"),
    )

    private fun option(device: Device, runtime: String, kind: String): EngineOption {
        val id = "asr-moonshine-v2-streaming-iree-$kind-x86_64"
        val missing = IreeMoonshineStream.unavailableReason()
            ?: if (File(packs, "$id/descriptor.json").isFile) null else "no pack at ${File(packs, id)}: run scripts/materialize-desktop.sh"
        return EngineOption(device, runtime, id, missing)
    }

    /** The cartridge of [option], for callers that want its stage timings as well. */
    fun cartridge(option: EngineOption): DesktopMoonshineCartridge =
        DesktopMoonshineCartridge(PackDir(File(packs, option.pack).absolutePath))

    override fun open(option: EngineOption): SpeechEngine = CartridgeEngine(cartridge(option))

    override fun microphone(): Flow<FloatArray>? {
        val format = AudioFormat(SAMPLE_RATE_HZ.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val supported = try { AudioSystem.isLineSupported(info) } catch (_: Throwable) { false }
        if (!supported) return null
        return flow {
            val line = AudioSystem.getLine(info) as TargetDataLine
            line.open(format, FRAME_SAMPLES * 2 * 8)
            line.start()
            try {
                val bytes = ByteArray(FRAME_SAMPLES * 2)
                while (currentCoroutineContext().isActive) {
                    var filled = 0
                    while (filled < bytes.size) {
                        val n = line.read(bytes, filled, bytes.size - filled)
                        if (n <= 0) return@flow
                        filled += n
                    }
                    emit(FloatArray(FRAME_SAMPLES) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f })
                }
            } finally {
                runCatching { line.stop() }; runCatching { line.close() }
            }
        }.flowOn(Dispatchers.IO)
    }
}

/** The cartridge's streaming contract as the app's [SpeechEngine]: one session, opened with the engine. */
class CartridgeEngine(private val cartridge: StreamingAsrCartridge) : SpeechEngine {
    private val session = cartridge.open()
    override fun feed(pcm: FloatArray): String? = session.feed(pcm)
    override fun finish(): String = session.finish()
    override fun close() = cartridge.close()
}
