package sk.ainet.examples.smarthome.router

import kotlin.math.sqrt

/**
 * The input of the distilled router: a transcript as a bag of hashed character n-grams, L2-normalized.
 *
 * Trained on the host and evaluated on the phone, so it must give the same vector everywhere. That rules out
 * `String.hashCode` and locale-dependent lowercasing, so this uses FNV-1a over UTF-16 code units and an ASCII fold.
 * It is robust to the ASR's spelling noise ("twenty one" vs "twenty-one", missing punctuation).
 */
object RouterFeatures {
    /** Feature width: the router's input dimension. Changing it changes the model; it is pinned in the cartridge. */
    const val DIM = 512
    val ORDERS: IntArray = intArrayOf(2, 3, 4)
    const val SCHEME = "fnv1a-char-ngram-2-4-d512-l2-v1"

    /** `"Turn on the  Kitchen-light!"` → `" turn on the kitchen light "` (boundaries padded with one space). */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length + 2).append(' ')
        var space = true
        for (ch in text) {
            val c = when (ch) {
                in 'A'..'Z' -> ch + ('a' - 'A')
                in 'a'..'z', in '0'..'9' -> ch
                '\'' -> continue
                else -> ' '
            }
            if (c == ' ') { if (!space) sb.append(' '); space = true } else { sb.append(c); space = false }
        }
        if (!space) sb.append(' ')
        return sb.toString()
    }

    fun vector(text: String): FloatArray {
        val v = FloatArray(DIM)
        val s = normalize(text)
        for (n in ORDERS) for (i in 0..s.length - n) v[bucket(s, i, n)] += 1f
        var norm = 0f
        for (x in v) norm += x * x
        if (norm > 0f) { val inv = 1f / sqrt(norm); for (i in v.indices) v[i] *= inv }
        return v
    }

    internal fun bucket(s: String, from: Int, n: Int): Int {
        var h = 0x811C9DC5.toInt()
        for (i in from until from + n) { h = h xor s[i].code; h *= 0x01000193 }
        h = h xor n // the same characters at different orders land in different buckets
        return ((h.toLong() and 0xFFFFFFFFL) % DIM).toInt()
    }
}
