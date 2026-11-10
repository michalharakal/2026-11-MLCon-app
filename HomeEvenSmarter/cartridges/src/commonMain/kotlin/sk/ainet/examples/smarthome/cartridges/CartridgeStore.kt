package sk.ainet.examples.smarthome.cartridges

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import sk.ainet.examples.smarthome.cartridge.CartridgeDescriptor
import sk.ainet.examples.smarthome.cartridge.CartridgeExpectation
import sk.ainet.examples.smarthome.cartridge.CartridgeJson
import sk.ainet.examples.smarthome.cartridge.CartridgeManifest
import sk.ainet.examples.smarthome.cartridge.Ed25519
import sk.ainet.examples.smarthome.cartridge.ManifestSignatures
import sk.ainet.examples.smarthome.cartridge.SignatureStatus
import sk.ainet.examples.smarthome.cartridge.TrustedKey
import sk.ainet.examples.smarthome.cartridge.Verification
import kotlin.time.TimeSource

/** A materialized cartridge found on disk. [manifestJson] is the manifest as written, for its signature. */
data class InstalledCartridge(
    val dir: Path,
    val descriptor: CartridgeDescriptor,
    val manifest: CartridgeManifest,
    val manifestJson: JsonObject = JsonObject(emptyMap()),
) {
    val id: String get() = descriptor.id
    val task: String get() = descriptor.task
    val sizeBytes: Long get() = manifest.totalBytes
    val usesAccelerator: Boolean get() = descriptor.usesAccelerator
    fun file(relative: String): Path = Path(dir, relative)
}

/** Incremental SHA-256, from the platform (the JDK's on Android and desktop). */
interface StreamingSha256 {
    fun update(bytes: ByteArray, offset: Int, length: Int)
    fun hex(): String
}

internal expect fun platformSha256(): StreamingSha256
internal expect val platformEd25519: Ed25519

/**
 * Everything the app checks before it loads a cartridge: does it conform to what the pipeline declares, who signed
 * it, and are the bytes on disk the bytes the manifest lists. [digest] is the signed manifest digest, the identity
 * shown in the app.
 */
data class CartridgeCheck(
    val id: String,
    val version: String,
    val digest: String,
    val conformance: List<String>,
    val signature: SignatureStatus,
    val artifacts: Verification,
    val checkMs: Long,
) {
    val ok: Boolean get() = conformance.isEmpty() && signature.trusted && artifacts.ok
    val shortDigest: String get() = digest.removePrefix("sha256:").take(12)
    val problems: List<String> get() = conformance + (if (signature.trusted) emptyList() else listOf(signature.label)) + artifacts.problems
}

/**
 * The app's local cartridge directory: `<root>/<cartridge id>/{descriptor.json,manifest.json,artifacts/…}` —
 * exactly the pack_dir layout a materialization produces, so a pack can also be pushed with adb as it is.
 */
class CartridgeStore(
    val root: Path,
    private val sha256: () -> StreamingSha256 = ::platformSha256,
    private val ed25519: Ed25519 = platformEd25519,
) {
    private val fs = SystemFileSystem

    fun installed(): List<InstalledCartridge> {
        if (!fs.exists(root)) return emptyList()
        return fs.list(root).sortedBy { it.name }.mapNotNull { dir -> read(dir) }
    }

    fun read(dir: Path): InstalledCartridge? {
        val d = Path(dir, "descriptor.json"); val m = Path(dir, "manifest.json")
        if (!fs.exists(d) || !fs.exists(m)) return null
        return runCatching {
            val manifestText = readText(m)
            InstalledCartridge(
                dir, CartridgeJson.descriptor(readText(d)), CartridgeJson.manifest(manifestText),
                CartridgeJson.json.parseToJsonElement(manifestText).jsonObject,
            )
        }.getOrNull()
    }

    fun byId(id: String): InstalledCartridge? = read(Path(root, id))

    /**
     * The installed pack that conforms to [expectation], preferring one built for an accelerator unless [preferCpu].
     * Packs that do not conform are never chosen, however they are named.
     */
    fun select(expectation: CartridgeExpectation, preferCpu: Boolean = false): InstalledCartridge? =
        installed().filter { expectation.conforms(it.descriptor) }
            .sortedWith(compareBy<InstalledCartridge> { if (preferCpu) it.usesAccelerator else !it.usesAccelerator }.thenBy { it.id })
            .firstOrNull()

    /** Why each installed pack of the expected task was not selectable; for the log when nothing conforms. */
    fun rejected(expectation: CartridgeExpectation): Map<String, List<String>> =
        installed().filter { it.task == expectation.task }.associate { it.id to expectation.problems(it.descriptor) }.filterValues { it.isNotEmpty() }

    /** Streams every artifact through SHA-256 against the manifest. Reads gigabytes for the NLU pack — off the main thread. */
    fun verify(cartridge: InstalledCartridge): Verification {
        val problems = mutableListOf<String>()
        val buffer = ByteArray(1 shl 20)
        for (a in cartridge.manifest.artifacts) {
            val p = cartridge.file(a.path)
            val size = if (fs.exists(p)) fs.metadataOrNull(p)?.size else null
            if (size == null) { problems += "${a.path}: missing"; continue }
            if (size != a.size) { problems += "${a.path}: size $size != ${a.size}"; continue }
            val digest = sha256()
            fs.source(p).buffered().use { src ->
                while (true) { val n = src.readAtMostTo(buffer, 0, buffer.size); if (n <= 0) break; digest.update(buffer, 0, n) }
            }
            val actual = digest.hex(); val expected = a.digest.removePrefix("sha256:").lowercase()
            if (actual != expected) problems += "${a.path}: sha256 ${actual.take(12)}… != ${expected.take(12)}…"
        }
        return Verification(problems.isEmpty(), cartridge.manifest.artifacts.size, problems)
    }

    /** Conformance, signature and every artifact digest. The app refuses to load a pack whose check is not [CartridgeCheck.ok]. */
    fun check(cartridge: InstalledCartridge, expectation: CartridgeExpectation?, trusted: List<TrustedKey>): CartridgeCheck {
        val t0 = TimeSource.Monotonic.markNow()
        val digest = runCatching { ManifestSignatures.digest(cartridge.manifestJson) }.getOrDefault("sha256:?")
        val signature = ManifestSignatures.check(cartridge.manifestJson, trusted, ed25519)
        val artifacts = verify(cartridge)
        return CartridgeCheck(
            cartridge.id, cartridge.descriptor.version, digest,
            expectation?.problems(cartridge.descriptor).orEmpty(), signature, artifacts,
            t0.elapsedNow().inWholeMilliseconds,
        )
    }

    /** Cheap check: every artifact present with the manifest's size. */
    fun complete(cartridge: InstalledCartridge): Boolean = cartridge.manifest.artifacts.all { a ->
        val p = cartridge.file(a.path); fs.exists(p) && fs.metadataOrNull(p)?.size == a.size
    }

    fun delete(id: String) { deleteRecursively(Path(root, id)) }

    fun deleteRecursively(path: Path) {
        if (!fs.exists(path)) return
        if (fs.metadataOrNull(path)?.isDirectory == true) fs.list(path).forEach { deleteRecursively(it) }
        fs.delete(path, mustExist = false)
    }

    private fun readText(p: Path): String = fs.source(p).buffered().use { it.readString() }
}
