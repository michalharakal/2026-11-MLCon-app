# Moonshine Anywhere

One Kotlin Multiplatform app that runs [Moonshine v2 streaming](https://github.com/moonshine-ai/moonshine) speech-to-text on **Android, desktop JVM and iOS**, through the SKaiNET [Moonshine cartridge](https://github.com/SKaiNET-developers/SKaiNET-cartridge-blueprints) and IREE. No ONNX Runtime anywhere in the build.

It is the sibling of [HomeEvenSmarter](../HomeEvenSmarter/): that app shows the cartridge inside a pipeline, this one shows the cartridge alone, and asks a narrower question. Can a Moonshine user reach their platforms with it today?

## What works, measured

Same sample on every row: five seconds of a public-domain LibriVox reading, expected text *"For good or for evil, in the superlative degree of comparison only."* RTF is engine time per second of audio; below 1 keeps up with a live microphone.

| Platform | Engine | Transcript | RTF | Final decode | Load |
|---|---|---|---|---|---|
| Desktop JVM, Linux x86_64 (i7 laptop, 2019) | CPU · IREE local-task | exact | **0.25** | 160 ms | 0.6 s |
| Desktop JVM, Linux x86_64 | GPU · IREE Vulkan | not run | – | – | – |
| Android 14 TV box, armeabi-v7a (Amlogic, Mali) | CPU · IREE local-task | exact | 2.3 | 3.6 s | 3.5 s |
| Android 14 TV box, armeabi-v7a | GPU · IREE Vulkan | exact | 1.6 | 2.4 s | 4.5 s |
| Android, arm64-v8a | CPU and Vulkan | not run here | – | – | – |
| iOS | – | no engine yet | – | – | – |

"Exact" means every word; the engine adds one comma. Three things to read into this table:

- **The desktop Vulkan path is built but unproven.** Runtime and pack exist; the machine this was written on had no working Vulkan driver (NVIDIA kernel module and user-space library at different versions), so the engine could not create a device.
- **The TV box is slower than live audio on both engines.** It is a low-end 32-bit set-top SoC. HomeEvenSmarter measured the same cartridge on a Pixel 8a at RTF 1.0 on CPU, and no arm64 device was connected here.
- **The CPU packs leave speed on the table.** The desktop CPU pack ran at RTF 1.39 with the compile flags the blueprint uses for its Android CPU targets. Adding IREE's data tiling and microkernels (`--iree-global-opt-data-tiling --iree-llvmcpu-enable-ukernels=all`) took the encoder from 450 ms to 13 ms per window and RTF to 0.25. `scripts/materialize-desktop.sh` does that; the blueprint's `cpu-arm64` and `cpu-armv7` targets do not yet, and were not retried with it here.


Numbers come from `./gradlew :desktopApp:transcribe` and the Android activity's `--es run` mode (below): audio fed unpaced, three runs, typical value.

## Why this exists

On November 17–18, 2026, `github.com/microsoft/onnxruntime` and its releases started returning 404. Microsoft said it had "made some repositories private while we investigate malicious content", days after a compromised administrator account on its GitHub organization was used to publish poisoned `agent-framework` releases to PyPI. Microsoft has not said the two are connected, and nothing shows ONNX Runtime itself was tampered with. The repository still returned 404 on November 21, when this was written. Projects that fetch ONNX Runtime from GitHub at build or install time [stopped](https://github.com/pymupdf/PyMuPDF/issues/5287) [working](https://github.com/timoncool/speech-studio/issues/41). Moonshine's C++ core has one major dependency, and it is ONNX Runtime.

Moonshine's issue tracker shows what that dependency costs its users even on a normal day:

| What people hit | Where |
|---|---|
| Crash in ONNX Runtime on new Snapdragon CPUs (SME without SME2) | [#253](https://github.com/moonshine-ai/moonshine/issues/253) |
| Moonshine's bundled ONNX Runtime collides with another one in the same iOS app | [#235](https://github.com/moonshine-ai/moonshine/issues/235) |
| Library size on mobile: `libmoonshine.so` 11 MB plus ONNX Runtime 5 to 19 MB | [#242](https://github.com/moonshine-ai/moonshine/issues/242) |
| GPU: CUDA, DirectML and CoreML providers reject the graph or run slower than CPU | [#133](https://github.com/moonshine-ai/moonshine/issues/133) |
| JVM bindings requested, closed for lack of maintainer time | [#122](https://github.com/moonshine-ai/moonshine/issues/122) |
| Android ABIs beyond arm64, minSdk 35, 16 KB pages | [#174](https://github.com/moonshine-ai/moonshine/issues/174), [#197](https://github.com/moonshine-ai/moonshine/issues/197), [#120](https://github.com/moonshine-ai/moonshine/issues/120) |
| Intel Macs, old glibc on Linux x86_64 | [#137](https://github.com/moonshine-ai/moonshine/issues/137), [#206](https://github.com/moonshine-ai/moonshine/issues/206) |

The cartridge takes a different route: the model is defined in Kotlin, traced to StableHLO, compiled ahead of time by IREE for one target, and driven by a streaming runtime of about 1,200 lines of C. The desktop build of that runtime, with IREE's CPU and Vulkan drivers linked in, is 776 KB.

## What the app is

```
shared/        the engine contract, the utterance loop with its stopwatch, the Compose screen   (all platforms)
  androidMain  AndroidSpeechPlatform: the cartridge's own binding, AudioRecord
  jvmMain      DesktopSpeechPlatform: the binding the cartridge lacks on the JVM, javax.sound
  iosMain      IosSpeechPlatform: the screen, and an engine that says what is missing
androidApp/    one activity
desktopApp/    one window, plus the headless `transcribe` task
iosApp/        the SwiftUI host and an XcodeGen spec
native/        builds the streaming runtime for the desktop JVM
scripts/       builds and installs packs
```

Each platform is one file implementing `SpeechPlatform`: which packs exist, how one is opened, where the microphone is. Opening the cartridge is three lines on Android:

```kotlin
val cartridge = MoonshineAsrCartridge(PackDir(dir), "en", IreeMoonshineStream.VULKAN_DEVICE)
val session = cartridge.open()
session.feed(pcm)?.let(::showPartial)   // 16 kHz mono float, as it arrives
val text = session.finish()             // the exact final
```

## Build and run

Everything needs a checkout of [SKaiNET-cartridge-blueprints](https://github.com/SKaiNET-developers/SKaiNET-cartridge-blueprints) (`-PblueprintsDir=<path>`, or `blueprintsDir` in `~/.gradle/gradle.properties`; the scripts take `BLUEPRINTS_DIR`), JDK 21 and Docker with the `skainet/iree-compiler:3.11.0` and `skainet/iree-android:3.11.0` images from [SKaiNET-IREE-tools](https://github.com/SKaiNET-developers/SKaiNET-IREE-tools).

**Desktop (linux-x86_64)**

```bash
native/build-desktop-runtime.sh <SKaiNET-transformers>/llm-runtime/iree-android/native/moonshine_stream_jni.c
scripts/materialize-android.sh arm64 cpu       # any Android target once: it exports the graphs the next step compiles
scripts/materialize-desktop.sh                 # cpu-x86_64 and vulkan-x86_64 packs
./gradlew :desktopApp:run                      # the app
./gradlew :desktopApp:transcribe --args="cpu"  # headless; "gpu my.wav 5" for Vulkan, a file, five runs
```

**Android (arm64-v8a and armeabi-v7a)**

```bash
scripts/materialize-android.sh arm64           # or armv7; cpu and vulkan packs, unsigned, for measuring
./gradlew :androidApp:installDebug
adb shell am start -n sk.ainet.examples.moonshine/.MainActivity
scripts/install-android-packs.sh               # then "Rescan packs" in the app
adb shell am start -n sk.ainet.examples.moonshine/.MainActivity --es run gpu   # headless; adb logcat -s MoonshineAnywhere
```

Packs that HomeEvenSmarter already materialized work as they are: `scripts/install-android-packs.sh ../HomeEvenSmarter/build/cartridges`.

**iOS**

```bash
cd iosApp && xcodegen && open iosApp.xcodeproj   # on a Mac
```

## What does not work yet

These are gaps in the cartridge and its runtime, found by building this app. None is in this repository's power to close.

- **iOS has no engine.** The cartridge has no iOS target and SKaiNET-transformers ships no IREE runtime for iOS. The app's iOS source set compiles (checked on Linux, where it cannot be linked or run) and shows the screen with its one engine marked unavailable. Closing the gap needs IREE's CPU driver built for iOS, the CPU graphs compiled as static libraries (iOS does not load code from data files), and a C entry point next to the JNI one.
- **The desktop runtime is not published.** `IreeMoonshineStream` and `libskainet_moonshine_stream.so` exist for Android only. This app builds the library from the same C source and carries a JVM copy of the class, in the Android runtime's package because the JNI symbol names demand it. Both belong in SKaiNET-transformers as a JVM artifact, and `DesktopMoonshineCartridge` belongs in the cartridge's `jvmMain`.
- **Desktop packs are not blueprint targets.** `scripts/materialize-desktop.sh` compiles the blueprint's exported graphs by hand, so those packs have no manifest and no signature. Two more targets in `blueprint.json` would fix that. The fast CPU pack is also twice the size (342 MB against 177 MB): its decoder graphs carry their weights inline and the runtime still insists on the shared archive.
- **This app does not verify packs.** HomeEvenSmarter checks the manifest signature and every artifact digest before loading; here the packs are local, unsigned builds and are loaded as they are.
- **Windows and macOS** have neither runtime build nor pack. IREE supports both.
- **Utterances are capped at 5.12 s.** The encoder memory is 256 frames; the app ends a microphone utterance at 5 s. Moonshine's own library has no such limit.
- **A repeated phrase loses its second half.** *"It was the best of times, it was the worst of times"* comes back as *"It was the best of times."* on every clip of that shape tried here, while sentences without repetition come back whole. The runtime's guards against decoder loops (`repeat_period`, `drop_restart_suffix`) are the likely cause; this was not traced to the line.
- **Tiny, English and German only.** The runtime's geometry is compiled in for the tiny checkpoints. Moonshine ships small and medium streaming models and more languages.
- **No voice activity detection, speaker labels, intent recognition or text to speech.** Moonshine Voice has all four in one library.
