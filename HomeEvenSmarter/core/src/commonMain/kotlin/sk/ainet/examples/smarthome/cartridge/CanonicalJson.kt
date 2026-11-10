package sk.ainet.examples.smarthome.cartridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The cartridge spec's canonical JSON, the bytes a manifest signature covers:
 * `json.dumps(obj, sort_keys=True, separators=(",", ":"))` with `ensure_ascii=True`. Same construction as the
 * blueprint plugin's `CanonicalJson`, which signs. Manifests carry only strings, integers and booleans; a
 * floating-point number is refused rather than guessed, because Python's float repr is not reproduced here.
 */
object CanonicalJson {
    fun encode(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                e.keys.sortedWith(CODE_POINT_ORDER).forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    writeString(k, out); out.append(':'); write(e.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is JsonPrimitive -> when {
                e.isString -> writeString(e.content, out)
                e.content == "true" || e.content == "false" || INTEGER.matches(e.content) -> out.append(e.content)
                else -> throw IllegalArgumentException("not canonicalizable here: number ${e.content}")
            }
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\u000C' -> out.append("\\f")
            c.code < 0x20 || c.code > 0x7E -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
        out.append('"')
    }

    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

    /** Python sorts keys by code point; UTF-16 order differs only above the BMP, which sorting by code point fixes. */
    private val CODE_POINT_ORDER = Comparator<String> { a, b ->
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = codePointAt(a, i); val cb = codePointAt(b, j)
            if (ca != cb) return@Comparator ca.compareTo(cb)
            i += if (ca > 0xFFFF) 2 else 1; j += if (cb > 0xFFFF) 2 else 1
        }
        (a.length - i).compareTo(b.length - j)
    }

    private fun codePointAt(s: String, i: Int): Int {
        val hi = s[i]
        if (hi.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return ((hi.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
        }
        return hi.code
    }
}
