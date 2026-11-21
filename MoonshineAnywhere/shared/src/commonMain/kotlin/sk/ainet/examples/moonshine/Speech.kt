package sk.ainet.examples.moonshine

import kotlinx.coroutines.flow.Flow

/** Where the graphs of a pack run. */
enum class Device(val label: String) { CPU("CPU"), GPU("GPU") }

/**
 * One way to run the Moonshine cartridge on this platform: a device, the runtime behind it and the pack it needs.
 * [unavailable] says why it cannot be opened right now (no pack on disk, no runtime for this platform), else `null`.
 */
data class EngineOption(
    val device: Device,
    /** The runtime and its driver, as shown in the UI: `IREE · local-task`, `IREE · Vulkan`. */
    val runtime: String,
    /** Cartridge id of the pack this option loads. */
    val pack: String,
    val unavailable: String? = null,
) {
    val available: Boolean get() = unavailable == null
}

/**
 * A loaded cartridge, one utterance at a time: 16 kHz mono float PCM in, cumulative partial transcripts out, one
 * exact final on [finish]. This is the cartridge's `StreamingAsrSession`, restated here because the cartridge
 * library has no iOS variant yet and this module compiles for iOS too.
 */
interface SpeechEngine : AutoCloseable {
    /** Returns the updated cumulative partial, or `null` when nothing changed. */
    fun feed(pcm: FloatArray): String?

    /** The exact final transcript; the engine is then ready for the next utterance. */
    fun finish(): String
}

/** Everything platform-specific the app needs. One implementation per platform, each a single small file. */
interface SpeechPlatform {
    /** Shown in the header: platform, device, ABI. */
    val name: String

    /** CPU first, then GPU where the platform has one. Unavailable options are listed with their reason. */
    fun engines(): List<EngineOption>

    /** Loads the pack of [option]. Blocking, and slow on a GPU (the graphs are compiled for the device on load). */
    fun open(option: EngineOption): SpeechEngine

    /** The microphone as 16 kHz mono float frames of 80 ms, or `null` where the platform has none wired up. */
    fun microphone(): Flow<FloatArray>?
}

const val SAMPLE_RATE_HZ: Int = 16_000

/** 80 ms, the chunk size the cartridge's descriptor declares. */
const val FRAME_SAMPLES: Int = 1_280

/** The cartridge's window: `max_utterance_s` = 5.12 in its descriptor. The app ends an utterance just before. */
const val MAX_UTTERANCE_SAMPLES: Int = 5 * SAMPLE_RATE_HZ
