package sk.ainet.examples.moonshine

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import sk.ainet.cartridge.asr.moonshine.MoonshineAsrCartridge
import sk.ainet.cartridge.asr.moonshine.PackDir
import sk.ainet.cartridge.asr.moonshine.StreamingAsrCartridge
import sk.ainet.transformers.iree.android.IreeMoonshineStream
import java.io.File

/**
 * Android: the cartridge's own binding (`MoonshineAsrCartridge`), CPU through IREE's local-task driver and GPU
 * through Vulkan, the microphone through AudioRecord.
 *
 * Packs live in the app's private storage, `files/cartridges/<id>`: the CPU graphs are native libraries the runtime
 * loads, which Android allows from there only. `scripts/install-android-packs.sh` pushes them to the app's external
 * files directory; the first [open] moves them across.
 */
class AndroidSpeechPlatform(private val context: Context) : SpeechPlatform {
    private val abi: String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    private val abiTag: String? = when (abi) { "arm64-v8a" -> "arm64"; "armeabi-v7a" -> "armv7"; else -> null }

    override val name: String = "Android ${Build.VERSION.RELEASE} · ${Build.MODEL} · $abi"

    override fun engines(): List<EngineOption> = listOf(
        option(Device.CPU, "IREE · local-task", "cpu"),
        option(Device.GPU, "IREE · Vulkan", "vulkan"),
    )

    private fun option(device: Device, runtime: String, kind: String): EngineOption {
        val id = "asr-moonshine-v2-streaming-iree-$kind-${abiTag ?: abi}"
        val missing = when {
            abiTag == null -> "the cartridge has ARM targets only (this device is $abi)"
            File(installed(id), "descriptor.json").isFile || File(staged(id), "descriptor.json").isFile -> null
            else -> "no pack on the device: scripts/install-android-packs.sh"
        }
        return EngineOption(device, runtime, id, missing)
    }

    private fun installed(id: String) = File(context.filesDir, "cartridges/$id")
    private fun staged(id: String) = File(context.getExternalFilesDir(null), "cartridges/$id")

    override fun open(option: EngineOption): SpeechEngine {
        val dir = installed(option.pack)
        val staged = staged(option.pack)
        val stagedDescriptor = File(staged, "descriptor.json")
        // A pushed pack replaces the installed one when it is newer. The app cannot always delete what adb wrote,
        // so the timestamps decide, not whether the staged copy is still there.
        if (stagedDescriptor.isFile && stagedDescriptor.lastModified() > File(dir, "descriptor.json").lastModified()) {
            dir.deleteRecursively()
            staged.copyRecursively(dir, overwrite = true)
            staged.deleteRecursively()
        }
        val device = if (option.device == Device.GPU) IreeMoonshineStream.VULKAN_DEVICE else IreeMoonshineStream.CPU_DEVICE
        return CartridgeEngine(MoonshineAsrCartridge(PackDir(dir.absolutePath), "en", device))
    }

    override fun microphone(): Flow<FloatArray>? {
        if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)) return null
        return flow {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val recorder = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuffer, FRAME_SAMPLES * 16))
            } catch (e: SecurityException) {
                throw IllegalStateException("microphone permission not granted", e)
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); throw IllegalStateException("the microphone could not be opened (permission granted?)") }
            try {
                recorder.startRecording()
                val frame = FloatArray(FRAME_SAMPLES)
                while (currentCoroutineContext().isActive) {
                    var filled = 0
                    while (filled < FRAME_SAMPLES) {
                        val n = recorder.read(frame, filled, FRAME_SAMPLES - filled, AudioRecord.READ_BLOCKING)
                        if (n < 0) throw IllegalStateException("AudioRecord.read returned $n")
                        filled += n
                    }
                    emit(frame.copyOf())
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }.flowOn(Dispatchers.IO)
    }
}

/** The cartridge's streaming contract as the app's [SpeechEngine]: one session, opened with the engine. */
class CartridgeEngine(private val cartridge: StreamingAsrCartridge) : SpeechEngine {
    private val session = cartridge.open()
    override fun feed(pcm: FloatArray): String? = session.feed(pcm)
    override fun finish(): String = session.finish()
    override fun close() = cartridge.close()
}
