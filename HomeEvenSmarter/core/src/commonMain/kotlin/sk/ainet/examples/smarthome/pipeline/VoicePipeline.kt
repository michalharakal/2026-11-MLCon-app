package sk.ainet.examples.smarthome.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import sk.ainet.examples.smarthome.actions.ActionRouter
import sk.ainet.examples.smarthome.engine.AsrEngine
import sk.ainet.examples.smarthome.engine.NluEngine
import sk.ainet.examples.smarthome.hybrid.IntentEscalation
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Executes a [VoiceSpec] stage by stage and publishes every step as a [PipelineEvent]. One utterance at a time; the
 * caller decides where an utterance ends by completing the frame flow (push-to-talk release, or an endpointer).
 * [runText] enters after the ASR stage, for typed input and the golden set.
 */
class VoicePipeline(val spec: VoiceSpec) {
    constructor(
        asr: AsrEngine?,
        nlu: NluEngine,
        router: ActionRouter,
        escalation: IntentEscalation = IntentEscalation.None,
        nluBudgetMs: Long = 15_000,
        asrDispatcher: CoroutineDispatcher = Dispatchers.Default,
        nluDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(homeVoice(asr, nlu, router, escalation, nluBudgetMs, asrDispatcher, nluDispatcher))

    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(PipelineState.IDLE)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val clock = TimeSource.Monotonic.markNow()

    /** Runs one spoken utterance: consumes [frames] until the flow completes, then resolves and acts. */
    suspend fun runUtterance(frames: Flow<FloatArray>, source: String = "mic"): PipelineRun =
        execute(Utterance(frames, source), firstStage = 0)

    /** Runs the stages after ASR on typed text. */
    suspend fun runText(text: String): PipelineRun {
        val afterAsr = spec.stages.indexOfFirst { Stage.ASR in it.shownAs } + 1
        return execute(Transcript(text.trim()), firstStage = afterAsr) {
            emit(PipelineEvent.Started(t0, "text"))
            emit(PipelineEvent.Transcribed(now(), text, 0, 0))
        }
    }

    private suspend fun execute(input: Any, firstStage: Int, before: StageScope.() -> Unit = {}): PipelineRun {
        val run = StageScope(_events, _state, clock, clock.elapsedNow().inWholeMilliseconds)
        try {
            run.before()
            var value: Any? = input
            for (stage in spec.stages.drop(firstStage)) {
                // the Chain that built the spec guarantees each stage accepts the previous stage's output
                @Suppress("UNCHECKED_CAST")
                val body = stage.body as suspend StageScope.(Any) -> Any?
                value = run.body(value!!) ?: break
            }
        } catch (e: CancellationException) {
            _state.value = PipelineState.IDLE
            throw e
        }
        val total = run.now() - run.t0
        run.emit(PipelineEvent.Finished(run.now(), total))
        _state.value = PipelineState.IDLE
        return run.toRun(total)
    }

    companion object {
        fun rms(frame: FloatArray): Float {
            if (frame.isEmpty()) return 0f
            var acc = 0.0
            for (s in frame) acc += s * s
            return sqrt(acc / frame.size).toFloat()
        }
    }
}
