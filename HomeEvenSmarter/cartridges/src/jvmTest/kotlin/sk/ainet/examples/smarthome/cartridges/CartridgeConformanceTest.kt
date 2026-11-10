package sk.ainet.examples.smarthome.cartridges

import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import sk.ainet.examples.smarthome.cartridge.CartridgeJson
import sk.ainet.examples.smarthome.cartridge.HomeCartridges
import sk.ainet.examples.smarthome.cartridge.ManifestSignatures
import sk.ainet.examples.smarthome.cartridge.SignatureStatus
import sk.ainet.examples.smarthome.cartridge.TrustedKey
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The checks the app runs before loading a cartridge, against real packs: the fixtures are a manifest and
 * descriptors exactly as the blueprint plugin materialized and signed them for the phone.
 */
class CartridgeConformanceTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private val signed: JsonObject = CartridgeJson.json.parseToJsonElement(fixture("asr-vulkan.manifest.json")).jsonObject

    @Test
    fun `canonical manifest digest matches the spec's python construction`() {
        // python3: hashlib.sha256(json.dumps(m_without_signatures, sort_keys=True, separators=(",", ":")).encode())
        assertEquals("sha256:cfd10b29cbf17cbcae9107cca60d03fbc460d6f04b962bdada9d13f422420813", ManifestSignatures.digest(signed))
    }

    @Test
    fun `a pack signed by the blueprint plugin verifies against the pinned key`() {
        val status = ManifestSignatures.check(signed, HomeCartridges.trustedKeys, platformEd25519)
        assertEquals(SignatureStatus.Verified("homeevensmarter-dev", "Michal Harakal"), status)
    }

    @Test
    fun `changing one artifact digest in the manifest breaks the signature`() {
        val artifacts = signed.getValue("artifacts").jsonArray
        val first = artifacts[0].jsonObject
        val tampered = JsonObject(signed + ("artifacts" to JsonArray(listOf(JsonObject(first + ("digest" to JsonPrimitive("sha256:" + "0".repeat(64))))) + artifacts.drop(1))))
        assertIs<SignatureStatus.Invalid>(ManifestSignatures.check(tampered, HomeCartridges.trustedKeys, platformEd25519))
    }

    @Test
    fun `unsigned and foreign-key manifests are not trusted`() {
        assertEquals(SignatureStatus.Unsigned, ManifestSignatures.check(JsonObject(signed - "signatures"), HomeCartridges.trustedKeys, platformEd25519))
        val other = TrustedKey("someone-else", HomeCartridges.trustedKeys.single().raw)
        assertEquals(SignatureStatus.UnknownKey("homeevensmarter-dev"), ManifestSignatures.check(signed, listOf(other), platformEd25519))
    }

    @Test
    fun `real descriptors conform to the pipeline's expectations and only to their own`() {
        val asr = CartridgeJson.descriptor(fixture("asr-vulkan.descriptor.json"))
        val nluGpu = CartridgeJson.descriptor(fixture("nlu-vulkan.descriptor.json"))
        val nluCpu = CartridgeJson.descriptor(fixture("nlu-cpu.descriptor.json"))
        assertEquals(emptyList(), HomeCartridges.Asr.problems(asr))
        assertEquals(emptyList(), HomeCartridges.Nlu.problems(nluGpu))
        assertEquals(emptyList(), HomeCartridges.Nlu.problems(nluCpu))
        assertFalse(HomeCartridges.Nlu.conforms(asr))
        assertTrue(HomeCartridges.Asr.problems(asr.copy(version = "9.9.9")).single().startsWith("version"))
        val wrongRate = asr.copy(io = asr.io!!.copy(input = asr.io!!.input.copy(sampleRateHz = 8_000)))
        assertEquals(listOf("sample rate 8000 != 16000 Hz"), HomeCartridges.Asr.problems(wrongRate))
    }

    // ---- the store, end to end on disk with a throwaway key ------------------------------------------------

    private val keys = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    private val testKey = TrustedKey("test", (keys.public as Ed25519PublicKeyParameters).encoded)

    /** Writes a pack_dir with one artifact and a manifest signed by [keys], the way the blueprint plugin lays it out. */
    private fun pack(root: File, descriptorFixture: String, id: String, content: ByteArray = "weights".encodeToByteArray()): File {
        val dir = File(root, id).apply { mkdirs() }
        val descriptor = CartridgeJson.json.parseToJsonElement(fixture(descriptorFixture)).jsonObject.let { JsonObject(it + ("id" to JsonPrimitive(id))) }
        File(dir, "descriptor.json").writeText(descriptor.toString())
        File(dir, "artifacts/weights").apply { parentFile.mkdirs() }.writeBytes(content)
        val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val unsigned = buildJsonObject {
            put("schema_version", "2"); put("kind", "cartridge"); put("id", id); put("version", descriptor.getValue("version").toString().trim('"'))
            put("digest_alg", "sha256")
            put("artifacts", JsonArray(listOf(buildJsonObject { put("role", "weights"); put("path", "artifacts/weights"); put("digest", "sha256:$digest"); put("size", content.size) })))
        }
        val message = ManifestSignatures.digest(unsigned).removePrefix("sha256:").encodeToByteArray()
        val sig = Ed25519Signer().run { init(true, keys.private as Ed25519PrivateKeyParameters); update(message, 0, message.size); generateSignature() }
        val manifest = JsonObject(unsigned + ("signatures" to JsonArray(listOf(buildJsonObject {
            put("keyid", "test"); put("alg", "ed25519"); put("signer", "test"); put("signature", Base64.getEncoder().encodeToString(sig))
        }))))
        File(dir, "manifest.json").writeText(manifest.toString())
        return dir
    }

    @Test
    fun `store checks signature and every byte, and refuses a tampered artifact`() {
        val root = Files.createTempDirectory("carts").toFile()
        val dir = pack(root, "nlu-vulkan.descriptor.json", "nlu-a-vulkan")
        val store = CartridgeStore(Path(root.absolutePath))
        val cart = store.installed().single()

        val ok = store.check(cart, HomeCartridges.Nlu, listOf(testKey))
        assertTrue(ok.ok, "${ok.problems}")
        assertEquals(SignatureStatus.Verified("test", "test"), ok.signature)
        assertEquals(1, ok.artifacts.checked)

        assertFalse(store.check(cart, HomeCartridges.Nlu, HomeCartridges.trustedKeys).ok, "a key the app does not pin must not pass")

        File(dir, "artifacts/weights").writeBytes("weightz".encodeToByteArray())  // same size, one byte off
        val tampered = store.check(cart, HomeCartridges.Nlu, listOf(testKey))
        assertFalse(tampered.ok)
        assertTrue(tampered.signature.trusted, "the manifest is intact; the bytes on disk are not")
        assertTrue(tampered.problems.single().contains("sha256"), "${tampered.problems}")
    }

    @Test
    fun `selection is by conformance, preferring the accelerator unless asked for cpu`() {
        val root = Files.createTempDirectory("carts").toFile()
        pack(root, "nlu-cpu.descriptor.json", "a-nlu-cpu")
        pack(root, "nlu-vulkan.descriptor.json", "b-nlu-vulkan")
        pack(root, "asr-vulkan.descriptor.json", "0-asr")   // sorts first: a by-directory pick would take it
        val store = CartridgeStore(Path(root.absolutePath))
        assertEquals("b-nlu-vulkan", store.select(HomeCartridges.Nlu)?.id)
        assertEquals("a-nlu-cpu", store.select(HomeCartridges.Nlu, preferCpu = true)?.id)
        assertEquals("0-asr", store.select(HomeCartridges.Asr)?.id)
        val strict = HomeCartridges.Nlu.copy(versions = setOf("9.9.9"))
        assertNull(store.select(strict))
        assertEquals(setOf("a-nlu-cpu", "b-nlu-vulkan"), store.rejected(strict).keys)
    }
}
