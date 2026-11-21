package sk.ainet.examples.moonshine

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.flow.Flow
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import sk.ainet.examples.moonshine.ui.App

/**
 * iOS: the screen, the utterance loop and the sample are all here and shared; the engine is not. The cartridge has
 * no iOS variant and no iOS runtime: IREE's CPU driver would have to be built for iOS, and the CPU graphs compiled
 * as static libraries linked into the app (iOS does not load code from data files). Until then this platform lists
 * its engine and says what is missing, so the gap is visible in the app instead of in a build error.
 */
class IosSpeechPlatform : SpeechPlatform {
    override val name: String = "iOS ${UIDevice.currentDevice.systemVersion} · ${UIDevice.currentDevice.model}"

    override fun engines(): List<EngineOption> = listOf(
        EngineOption(
            Device.CPU, "IREE · local-sync", "asr-moonshine-v2-streaming-iree-cpu-ios-arm64",
            unavailable = "the cartridge has no iOS target yet: it needs an IREE runtime built for iOS and statically linked CPU graphs",
        ),
    )

    override fun open(option: EngineOption): SpeechEngine = throw UnsupportedOperationException(option.unavailable)

    override fun microphone(): Flow<FloatArray>? = null
}

/** Entry point for the Swift side: `MainViewControllerKt.MainViewController()`. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { App(IosSpeechPlatform()) }
