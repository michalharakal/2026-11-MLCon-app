package sk.ainet.transformers.iree.android

import java.io.File
import java.nio.file.Files as NioFiles
import java.nio.file.StandardCopyOption

/**
 * The desktop JVM twin of SKaiNET-transformers' Android `IreeMoonshineStream`: the same six native methods over the
 * same C source, built for linux-x86_64 by `native/build-desktop-runtime.sh`.
 *
 * It sits in the Android runtime's package, under its name, because that is the JNI symbol contract of
 * `libskainet_moonshine_stream.so` (`Java_sk_ainet_transformers_iree_android_IreeMoonshineStream_*`). Only the
 * library lookup differs: Android finds the library in the APK, here it comes out of the jar. Once the runtime
 * ships a JVM artifact, this file goes away and the dependency takes its place.
 *
 * `frontend_step.vmfb`, the optional incremental frontend, is picked up by name next to `frontend.vmfb`.
 */
public class IreeMoonshineStream(device: String, files: Files) : AutoCloseable {
    public data class Files(
        val frontendVmfb: String, val encoderVmfb: String, val adapterVmfb: String, val prefillVmfb: String,
        val stepVmfb: String, val paramsIrpa: String, val vocabBin: String, val decEmbedBin: String,
    )

    private var handle: Long = 0

    init {
        loadNativeLibrary()
        handle = nativeCreate(
            device, files.frontendVmfb, files.encoderVmfb, files.adapterVmfb, files.prefillVmfb, files.stepVmfb,
            files.paramsIrpa, files.vocabBin, files.decEmbedBin,
        )
        check(handle != 0L) { "IreeMoonshineStream: native create failed (device='$device'): check the model files and, for 'vulkan', that a Vulkan driver is installed" }
    }

    public fun feedPcm(samples: FloatArray): String? = if (handle == 0L || samples.isEmpty()) null else nativeFeedPcm(handle, samples)

    public fun finish(): String? = if (handle == 0L) null else nativeFinish(handle)

    public fun reset() { if (handle != 0L) nativeReset(handle) }

    /** The stage timings of the last [finish], as the runtime reports them (`key=value` pairs). */
    public fun stats(): Map<String, String> {
        if (handle == 0L) return emptyMap()
        val raw = nativeStats(handle) ?: return emptyMap()
        return raw.split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    override fun close() { if (handle != 0L) { nativeDestroy(handle); handle = 0 } }

    private external fun nativeCreate(
        device: String, fe: String, enc: String, adp: String, prefill: String, step: String,
        params: String, vocab: String, embed: String,
    ): Long
    private external fun nativeFeedPcm(handle: Long, pcm: FloatArray): String?
    private external fun nativeFinish(handle: Long): String?
    private external fun nativeReset(handle: Long)
    private external fun nativeStats(handle: Long): String?
    private external fun nativeDestroy(handle: Long)

    public companion object {
        public const val VULKAN_DEVICE: String = "vulkan"
        public const val CPU_DEVICE: String = "local-task"

        private const val LIBRARY = "libskainet_moonshine_stream.so"

        /** `linux-x86_64`, the one desktop platform the runtime has been built for so far; `null` elsewhere. */
        public val platformTag: String? by lazy {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            if ("linux" in os && arch in setOf("amd64", "x86_64")) "linux-x86_64" else null
        }

        /** Why the runtime cannot be loaded here, or `null` when it can. Cheap: does not load anything. */
        public fun unavailableReason(): String? {
            System.getProperty("moonshine.native")?.let { return if (File(it).isFile) null else "moonshine.native points at a missing file: $it" }
            val tag = platformTag ?: return "the desktop runtime is built for linux-x86_64 only (this is ${System.getProperty("os.name")} ${System.getProperty("os.arch")})"
            return if (IreeMoonshineStream::class.java.getResource("/native/$tag/$LIBRARY") != null) null
            else "runtime library not built: run native/build-desktop-runtime.sh"
        }

        @Volatile private var loaded = false

        private fun loadNativeLibrary() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                unavailableReason()?.let { throw IllegalStateException(it) }
                val path = System.getProperty("moonshine.native") ?: run {
                    val out = NioFiles.createTempFile("skainet_moonshine_stream", ".so")
                    out.toFile().deleteOnExit()
                    IreeMoonshineStream::class.java.getResourceAsStream("/native/$platformTag/$LIBRARY")!!.use {
                        NioFiles.copy(it, out, StandardCopyOption.REPLACE_EXISTING)
                    }
                    out.toString()
                }
                System.load(path)
                loaded = true
            }
        }
    }
}
