package sk.ainet.examples.moonshine

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import sk.ainet.examples.moonshine.ui.Screen
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real thing on this machine: the CPU pack through the desktop runtime, on the bundled sample. Skipped (with a
 * message) when the runtime library or the pack has not been built, so a fresh checkout still passes `check`.
 */
class DesktopCartridgeTest {
    private val platform = DesktopSpeechPlatform()
    private val cpu = platform.engines().first { it.device == Device.CPU }

    private fun words(text: String) = text.lowercase().filter { it.isLetter() || it == ' ' }.split(' ').filter(String::isNotEmpty)

    @Test fun cpuPackTranscribesTheSample() {
        cpu.unavailable?.let { println("skipped: $it"); return }
        platform.open(cpu).use { engine ->
            val partials = mutableListOf<String>()
            val first = runBlocking { transcribe(engine, sampleUtterance()) { partials += it } }
            assertEquals(words(SAMPLE_TEXT), words(first.text))
            assertTrue(partials.isNotEmpty(), "no partial transcripts while streaming")
            assertEquals(5000, first.audioMs)
            // the session is reusable: a second utterance on the same engine gives the same transcript
            assertEquals(first.text, runBlocking { transcribe(engine, sampleUtterance()) }.text)
        }
    }

    /** Renders the shared screen off-screen with a real result, to build/screenshot.png. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun screenRendersWithARealResult() {
        cpu.unavailable?.let { println("skipped: $it"); return }
        val result = platform.open(cpu).use { runBlocking { transcribe(it, sampleUtterance()) } }
        val state = Transcriber.State(platform.name, platform.engines(), cpu, hasMicrophone = true, partial = result.text, result = result, loadMs = 1200)
        val png = ImageComposeScene(760, 720, Density(1f), Dispatchers.Unconfined) { Screen(state) }.use { scene ->
            scene.render(0).close()
            scene.render(16_666_667).use { it.encodeToData(EncodedImageFormat.PNG)!!.bytes }
        }
        File("build/screenshot.png").apply { parentFile.mkdirs() }.writeBytes(png)
        assertTrue(png.size > 10_000, "the screen rendered to almost nothing")
    }
}
