package sk.ainet.examples.moonshine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * The app behind the screen: pick an engine, run an utterance from the microphone or the bundled sample, show the
 * partials as they come and what the utterance cost. Engines are loaded on first use and kept until another one is
 * picked; every engine call happens on one worker so a session never sees two threads.
 */
class Transcriber(private val platform: SpeechPlatform, private val scope: CoroutineScope) {
    enum class Phase { Idle, Loading, Listening, Sample, Finishing }

    data class State(
        val platform: String,
        val engines: List<EngineOption>,
        val selected: EngineOption?,
        val hasMicrophone: Boolean,
        val phase: Phase = Phase.Idle,
        val partial: String = "",
        val result: UtteranceResult? = null,
        /** How long the selected engine took to load, once it has. */
        val loadMs: Long? = null,
        val error: String? = null,
    ) {
        val busy: Boolean get() = phase != Phase.Idle
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private var engine: SpeechEngine? = null
    private var engineOption: EngineOption? = null
    private var job: Job? = null
    @Volatile private var stopRequested = false

    private val _state = MutableStateFlow(
        platform.engines().let { all ->
            State(platform.name, all, all.firstOrNull { it.available } ?: all.firstOrNull(), platform.microphone() != null)
        },
    )
    val state: StateFlow<State> = _state.asStateFlow()

    fun select(option: EngineOption) {
        if (_state.value.busy || option == _state.value.selected) return
        _state.update { it.copy(selected = option, loadMs = null, result = null, partial = "", error = null) }
    }

    /** Looks for packs again: they can arrive (adb push, a finished compile) while the app is open. */
    fun refresh() {
        if (_state.value.busy) return
        val all = platform.engines()
        _state.update { s -> s.copy(engines = all, selected = all.firstOrNull { it.device == s.selected?.device } ?: all.firstOrNull()) }
    }

    fun runSample() = run(Phase.Sample) { e ->
        transcribe(e, sampleUtterance(), paced = true) { p -> _state.update { it.copy(partial = p) } }
    }

    /** Starts listening, or ends the utterance when already listening. An utterance also ends at the window's 5 s. */
    fun toggleMicrophone() {
        if (_state.value.phase == Phase.Listening) { stopRequested = true; return }
        val mic = platform.microphone() ?: return
        run(Phase.Listening) { e ->
            val meter = UtteranceMeter(e)
            mic.takeWhile { !stopRequested && meter.audioSamples < MAX_UTTERANCE_SAMPLES }
                .collect { frame -> meter.feed(frame)?.let { p -> _state.update { it.copy(partial = p) } } }
            _state.update { it.copy(phase = Phase.Finishing) }
            meter.finish()
        }
    }

    private fun run(phase: Phase, utterance: suspend (SpeechEngine) -> UtteranceResult) {
        val option = _state.value.selected ?: return
        if (_state.value.busy || !option.available) return
        stopRequested = false
        _state.update { it.copy(phase = Phase.Loading, partial = "", result = null, error = null) }
        job = scope.launch(worker) {
            try {
                val e = engineFor(option)
                _state.update { it.copy(phase = phase) }
                val result = utterance(e)
                _state.update { it.copy(result = result, partial = result.text) }
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: t.toString()) }
            } finally {
                _state.update { it.copy(phase = Phase.Idle) }
            }
        }
    }

    private fun engineFor(option: EngineOption): SpeechEngine {
        engine?.let { if (engineOption == option) return it }
        engine?.close(); engine = null; engineOption = null
        val mark = TimeSource.Monotonic.markNow()
        val opened = platform.open(option)
        engine = opened; engineOption = option
        _state.update { it.copy(loadMs = mark.elapsedNow().inWholeMilliseconds) }
        return opened
    }

    fun close() {
        job?.cancel()
        scope.launch(worker) { engine?.close(); engine = null; engineOption = null }
    }
}
