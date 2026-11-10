package sk.ainet.examples.smarthome.cartridges

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import sk.ainet.examples.smarthome.cartridge.Ed25519
import java.security.MessageDigest

/** The JDK digest: hardware-accelerated SHA-256, fast enough to check gigabytes of weights before every load. */
internal actual fun platformSha256(): StreamingSha256 = object : StreamingSha256 {
    private val md = MessageDigest.getInstance("SHA-256")
    override fun update(bytes: ByteArray, offset: Int, length: Int) = md.update(bytes, offset, length)
    override fun hex(): String = md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** BouncyCastle's lightweight Ed25519, independent of the platform provider (Android has Ed25519 only from API 33). */
internal actual val platformEd25519: Ed25519 = Ed25519 { publicKey, message, signature ->
    runCatching {
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(message, 0, message.size)
            verifySignature(signature)
        }
    }.getOrDefault(false)
}
