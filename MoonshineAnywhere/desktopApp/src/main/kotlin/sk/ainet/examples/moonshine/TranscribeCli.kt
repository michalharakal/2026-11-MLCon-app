package sk.ainet.examples.moonshine

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * The cartridge without a window: `transcribe <cpu|gpu> [file.wav] [runs]`. Prints the transcript and what each run
 * cost; audio is fed as fast as the engine takes it, so the real-time factor is pure engine time.
 */
fun main(args: Array<String>) {
    val device = when (args.getOrNull(0)?.lowercase()) {
        "cpu" -> Device.CPU
        "gpu", "vulkan" -> Device.GPU
        else -> { System.err.println("usage: transcribe <cpu|gpu> [file.wav (16 kHz, 16-bit)] [runs]"); exitProcess(2) }
    }
    val wav = args.getOrNull(1)?.takeIf { it.endsWith(".wav", ignoreCase = true) }
    val runs = (args.getOrNull(if (wav == null) 1 else 2))?.toIntOrNull() ?: 3

    val platform = DesktopSpeechPlatform()
    val option = platform.engines().first { it.device == device }
    option.unavailable?.let { System.err.println("${device.label} is not available: $it"); exitProcess(1) }

    val pcm = runBlocking { if (wav != null) decodeWav(File(wav).readBytes()) else sampleUtterance() }
    if (pcm.size > MAX_UTTERANCE_SAMPLES) System.err.println("note: ${pcm.size / SAMPLE_RATE_HZ} s of audio, the cartridge's window is 5.12 s per utterance")
    println("${platform.name} · ${option.runtime} · ${option.pack}")
    println("audio: ${wav ?: "bundled sample, expected \"$SAMPLE_TEXT\""}, ${pcm.size * 1000L / SAMPLE_RATE_HZ} ms")

    val load = TimeSource.Monotonic.markNow()
    val cartridge = platform.cartridge(option)
    CartridgeEngine(cartridge).use { engine ->
        println("load: ${load.elapsedNow().inWholeMilliseconds} ms")
        repeat(runs) { i ->
            val r = runBlocking { transcribe(engine, pcm) }
            val rtf = "%.3f".format(java.util.Locale.ROOT, r.rtf)
            println("run ${i + 1}: \"${r.text}\"  rtf $rtf  stream ${r.streamMs} ms  final ${r.finishMs} ms  partials ${r.partials}")
            if (i == runs - 1) println("runtime stats: ${cartridge.stats().entries.joinToString(" ") { "${it.key}=${it.value}" }}")
        }
    }
}
