package sk.ainet.examples.moonshine

import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/** What one utterance cost. [computeMs] is time spent inside the engine only, so pacing does not inflate it. */
data class UtteranceResult(
    val text: String,
    val audioMs: Long,
    /** Engine time while audio was still arriving: frontend, encoder and the partial decodes. */
    val streamMs: Long,
    /** Engine time of the final exact decode. This is what the user waits for after they stop talking. */
    val finishMs: Long,
    val partials: Int,
    /** Audio time at which the first partial transcript appeared, or `null` when there was none. */
    val firstPartialAtMs: Long?,
) {
    val computeMs: Long get() = streamMs + finishMs

    /** Real-time factor: engine time per second of audio. Below 1 keeps up with a live microphone. */
    val rtf: Double get() = if (audioMs == 0L) 0.0 else computeMs.toDouble() / audioMs
}

/** Feeds one utterance to an engine and keeps the stopwatch. Not thread-safe: use it from the engine's thread. */
class UtteranceMeter(private val engine: SpeechEngine) {
    private var samples = 0
    private var streamNanos = 0L
    private var partials = 0
    private var firstPartialAtMs: Long? = null

    val audioSamples: Int get() = samples

    fun feed(pcm: FloatArray): String? {
        val mark = TimeSource.Monotonic.markNow()
        val partial = engine.feed(pcm)
        streamNanos += mark.elapsedNow().inWholeNanoseconds
        samples += pcm.size
        if (partial != null) {
            partials++
            if (firstPartialAtMs == null) firstPartialAtMs = samples * 1000L / SAMPLE_RATE_HZ
        }
        return partial
    }

    fun finish(): UtteranceResult {
        val mark = TimeSource.Monotonic.markNow()
        val text = engine.finish()
        val finishMs = mark.elapsedNow().inWholeMilliseconds
        return UtteranceResult(text.trim(), samples * 1000L / SAMPLE_RATE_HZ, streamNanos / 1_000_000, finishMs, partials, firstPartialAtMs)
    }
}

/**
 * Runs [pcm] through [engine] in 80 ms frames. With [paced] the frames arrive like a microphone would deliver them,
 * so partials show up as they would live; without, as fast as the engine takes them (the runtime then skips the
 * partial decodes it has no real-time budget for, which never changes the final).
 */
suspend fun transcribe(engine: SpeechEngine, pcm: FloatArray, paced: Boolean = false, onPartial: (String) -> Unit = {}): UtteranceResult {
    val meter = UtteranceMeter(engine)
    val start = TimeSource.Monotonic.markNow()
    var offset = 0
    while (offset < pcm.size) {
        val end = minOf(offset + FRAME_SAMPLES, pcm.size)
        if (paced) {
            val due = end * 1000L / SAMPLE_RATE_HZ
            val wait = due - start.elapsedNow().inWholeMilliseconds
            if (wait > 0) delay(wait)
        }
        meter.feed(pcm.copyOfRange(offset, end))?.let(onPartial)
        offset = end
    }
    return meter.finish()
}

/** 16-bit PCM WAV → mono float in [-1, 1]. Stereo is averaged; anything but 16 kHz is refused rather than resampled. */
fun decodeWav(bytes: ByteArray): FloatArray {
    fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
    fun tag(at: Int) = bytes.decodeToString(at, at + 4)
    require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "not a WAV file" }
    var channels = 0; var rate = 0; var bits = 0; var format = 0
    var at = 12
    while (at + 8 <= bytes.size) {
        val id = tag(at); val size = u32(at + 4); val body = at + 8
        when (id) {
            "fmt " -> { format = u16(body); channels = u16(body + 2); rate = u32(body + 4); bits = u16(body + 14) }
            "data" -> {
                require(format == 1 && bits == 16) { "only 16-bit PCM WAV is supported (format $format, $bits bit)" }
                require(rate == SAMPLE_RATE_HZ) { "the cartridge takes 16 kHz audio, this file is $rate Hz" }
                require(channels >= 1) { "WAV has no channels" }
                val frames = minOf(size, bytes.size - body) / (2 * channels)
                return FloatArray(frames) { f ->
                    var sum = 0
                    for (c in 0 until channels) sum += u16(body + (f * channels + c) * 2).toShort()
                    sum / (32768f * channels)
                }
            }
        }
        at = body + size + (size and 1)
    }
    throw IllegalArgumentException("WAV has no data chunk")
}
