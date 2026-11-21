package sk.ainet.examples.moonshine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UtteranceTest {
    /** Reports a partial every [every] samples and counts what it was fed. */
    private class CountingEngine(private val every: Int) : SpeechEngine {
        var fed = 0; var frames = 0
        override fun feed(pcm: FloatArray): String? {
            val before = fed / every
            fed += pcm.size; frames++
            return if (fed / every > before) "partial ${fed / every}" else null
        }
        override fun finish(): String = " final after $fed "
        override fun close() {}
    }

    private fun wav(samples: ShortArray, rate: Int = 16_000, channels: Int = 1): ByteArray {
        val data = samples.size * 2
        val out = ArrayList<Byte>()
        fun str(s: String) = s.forEach { out.add(it.code.toByte()) }
        fun le(v: Int, n: Int) = repeat(n) { out.add((v shr (8 * it)).toByte()) }
        str("RIFF"); le(36 + data, 4); str("WAVE")
        str("fmt "); le(16, 4); le(1, 2); le(channels, 2); le(rate, 4); le(rate * channels * 2, 4); le(channels * 2, 2); le(16, 2)
        str("LIST"); le(3, 4); out.addAll(listOf(1, 2, 3, 0))   // an odd-sized chunk before the data, padded
        str("data"); le(data, 4); samples.forEach { le(it.toInt(), 2) }
        return out.toByteArray()
    }

    @Test fun framesAreEightyMillisecondsAndTheTailIsKept() = runTest {
        val engine = CountingEngine(every = 4000)
        val partials = mutableListOf<String>()
        val result = transcribe(engine, FloatArray(10_000)) { partials += it }
        assertEquals(10_000, engine.fed)
        assertEquals(8, engine.frames)                       // 7 full frames of 1280 and a tail of 1040
        assertEquals(listOf("partial 1", "partial 2"), partials)
        assertEquals("final after 10000", result.text)
        assertEquals(625, result.audioMs)
        assertEquals(2, result.partials)
        assertEquals(320, result.firstPartialAtMs)           // the 4th frame, 5120 samples in
    }

    @Test fun noPartialsLeavesFirstPartialEmpty() = runTest {
        assertNull(transcribe(CountingEngine(every = 1_000_000), FloatArray(3000)).firstPartialAtMs)
    }

    @Test fun wavIsDecodedPastOtherChunks() {
        val pcm = decodeWav(wav(shortArrayOf(0, 16384, -32768, 32767)))
        assertEquals(listOf(0f, 0.5f, -1f, 32767 / 32768f), pcm.toList())
    }

    @Test fun stereoIsAveraged() {
        assertEquals(listOf(0.25f, 0f), decodeWav(wav(shortArrayOf(16384, 0, 8192, -8192), channels = 2)).toList())
    }

    @Test fun otherSampleRatesAreRefused() {
        assertFailsWith<IllegalArgumentException> { decodeWav(wav(shortArrayOf(1, 2), rate = 44_100)) }
        assertFailsWith<IllegalArgumentException> { decodeWav(byteArrayOf(1, 2, 3)) }
    }
}
