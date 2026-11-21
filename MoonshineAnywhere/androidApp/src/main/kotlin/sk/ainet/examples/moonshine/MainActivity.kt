package sk.ainet.examples.moonshine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.runBlocking
import sk.ainet.examples.moonshine.ui.App
import kotlin.concurrent.thread
import kotlin.time.TimeSource

class MainActivity : ComponentActivity() {
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val platform = AndroidSpeechPlatform(applicationContext)
        intent.getStringExtra("run")?.let { benchmark(platform, it, intent.getIntExtra("runs", 3)) }
        if (platform.microphone() != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent { App(platform) }
    }

    /**
     * The desktop `transcribe` task for a device, no screen needed:
     * `adb shell am start -n sk.ainet.examples.moonshine/.MainActivity --es run cpu` (or `gpu`), then
     * `adb logcat -s MoonshineAnywhere`. Runs the bundled sample unpaced and logs transcript and timings.
     */
    private fun benchmark(platform: AndroidSpeechPlatform, device: String, runs: Int) = thread(name = "moonshine-benchmark") {
        val tag = "MoonshineAnywhere"
        try {
            val wanted = if (device.equals("gpu", ignoreCase = true)) Device.GPU else Device.CPU
            val option = platform.engines().first { it.device == wanted }
            option.unavailable?.let { Log.e(tag, "${wanted.label} is not available: $it"); return@thread }
            Log.i(tag, "${platform.name} · ${option.runtime} · ${option.pack}")
            val load = TimeSource.Monotonic.markNow()
            platform.open(option).use { engine ->
                Log.i(tag, "load: ${load.elapsedNow().inWholeMilliseconds} ms")
                val pcm = runBlocking { sampleUtterance() }
                repeat(runs) { i ->
                    val r = runBlocking { transcribe(engine, pcm) }
                    Log.i(tag, "run ${i + 1}: \"${r.text}\"  rtf ${"%.3f".format(java.util.Locale.ROOT, r.rtf)}  stream ${r.streamMs} ms  final ${r.finishMs} ms  partials ${r.partials}")
                }
            }
            Log.i(tag, "done")
        } catch (t: Throwable) {
            Log.e(tag, "benchmark failed", t)
        }
    }
}
