package sk.ainet.examples.smarthome.cartridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Ed25519 signature check, supplied by the platform (BouncyCastle on Android and the JVM). */
fun interface Ed25519 {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/** A public key the app trusts, pinned in code. [raw] is the 32-byte Ed25519 key. */
class TrustedKey(val keyid: String, val raw: ByteArray) {
    companion object {
        /** From an X.509 SubjectPublicKeyInfo PEM as `openssl pkey -pubout` writes it. */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromPem(keyid: String, pem: String): TrustedKey {
            val der = Base64.decode(pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("").trim())
            require(der.size == 44 && der.copyOf(12).contentEquals(ED25519_SPKI_PREFIX)) { "not an Ed25519 public key: $keyid" }
            return TrustedKey(keyid, der.copyOfRange(12, 44))
        }

        private val ED25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    }
}

/** What a manifest's signature block says about who built the cartridge. */
sealed interface SignatureStatus {
    val label: String
    val trusted: Boolean get() = this is Verified

    data class Verified(val keyid: String, val signer: String) : SignatureStatus { override val label get() = "signed $keyid" }
    data object Unsigned : SignatureStatus { override val label get() = "unsigned" }
    data class UnknownKey(val keyid: String) : SignatureStatus { override val label get() = "untrusted key $keyid" }
    data class Invalid(val reason: String) : SignatureStatus { override val label get() = "bad signature: $reason" }
}

/**
 * Checks a manifest's ed25519 signature the way the cartridge spec signs it: over the ASCII hex SHA-256 of the
 * canonical manifest with `signatures` removed. Any trusted, valid entry is enough; the digest is also the
 * cartridge's identity shown in the app.
 */
object ManifestSignatures {
    /** `sha256:<hex>` of the canonical manifest without its signatures: what was signed. */
    fun digest(manifest: JsonObject): String =
        "sha256:" + Sha256.hex(CanonicalJson.encode(JsonObject(manifest - "signatures")).encodeToByteArray())

    @OptIn(ExperimentalEncodingApi::class)
    fun check(manifest: JsonObject, trusted: List<TrustedKey>, ed25519: Ed25519): SignatureStatus {
        val entries = (manifest["signatures"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        if (entries.isEmpty()) return SignatureStatus.Unsigned
        val message = try { digest(manifest).removePrefix("sha256:").encodeToByteArray() } catch (e: IllegalArgumentException) {
            return SignatureStatus.Invalid(e.message ?: "manifest not canonical")
        }
        var firstProblem: SignatureStatus? = null
        for (entry in entries) {
            val keyid = entry["keyid"]?.jsonPrimitive?.content ?: ""
            val alg = entry["alg"]?.jsonPrimitive?.content ?: ""
            if (alg != "ed25519") { firstProblem = firstProblem ?: SignatureStatus.Invalid("alg $alg"); continue }
            val key = trusted.firstOrNull { it.keyid == keyid }
            if (key == null) { firstProblem = firstProblem ?: SignatureStatus.UnknownKey(keyid); continue }
            val sig = runCatching { Base64.decode(entry["signature"]?.jsonPrimitive?.content ?: "") }.getOrNull()
            if (sig == null || sig.size != 64) { firstProblem = SignatureStatus.Invalid("malformed signature"); continue }
            if (ed25519.verify(key.raw, message, sig)) return SignatureStatus.Verified(keyid, entry["signer"]?.jsonPrimitive?.content ?: "")
            firstProblem = SignatureStatus.Invalid("does not match manifest ($keyid)")
        }
        return firstProblem ?: SignatureStatus.Unsigned
    }
}
