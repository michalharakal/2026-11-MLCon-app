package sk.ainet.examples.smarthome.pipeline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import sk.ainet.examples.smarthome.actions.ActionResult
import sk.ainet.examples.smarthome.engine.NluOutcome
import sk.ainet.examples.smarthome.hybrid.EscalationResult
import sk.ainet.examples.smarthome.tools.Correction
import kotlin.time.TimeMark

/** The audio contract of a pipeline. */
data class AudioFormat(val sampleRateHz: Int, val frameMs: Int) {
    val frameSamples: Int get() = sampleRateHz * frameMs / 1000
}

/** One utterance entering the pipeline: frames of mono float PCM, completed where the utterance ends. */
class Utterance(val frames: Flow<FloatArray>, val source: String)

data class Transcript(val text: String)

data class Understood(val transcript: String, val outcome: NluOutcome)

/** What the host decided to do with the model's answer. [call] is `null` when there is nothing to act on. */
data class Decision(val understood: Understood, val call: NluOutcome.Call?)

data class Completed(val action: ActionResult?, val escalation: EscalationResult?)

/**
 * One typed step of a pipeline, from [I] to [O]. The body returns `null` when it stopped the run through
 * [StageScope.fail].
 */
class VoiceStage<in I : Any, out O : Any> internal constructor(
    val id: String,
    /** The chips this stage draws on the pipeline panel; empty for stages that have no chip of their own. */
    val shownAs: List<Stage>,
    /** The engine or cartridge behind this stage, when there is one. */
    val engineId: String?,
    internal val body: suspend StageScope.(I) -> O?,
)

/** A pipeline under construction whose output so far is [O]; [then] only accepts a stage that consumes it. */
class Chain<O : Any> internal constructor(internal val stages: List<VoiceStage<*, *>>) {
    infix fun <N : Any> then(next: VoiceStage<O, N>): Chain<N> = Chain(stages + next)
}

/** A complete pipeline: an ordered, typed list of stages that [VoicePipeline] executes as written. */
class VoiceSpec internal constructor(
    val name: String,
    val audio: AudioFormat,
    val stages: List<VoiceStage<*, *>>,
) {
    /** The chips of the pipeline panel, in execution order, derived from the stages. */
    val panelStages: List<Stage> get() = listOf(Stage.LISTENING) + stages.flatMap { it.shownAs }
}

/**
 * Declares a voice pipeline. The types do the checking: a stage that does not accept the previous output, or a
 * pipeline that does not end in [Completed], does not compile.
 */
fun voicePipeline(name: String, audio: AudioFormat, define: (Chain<Utterance>) -> Chain<Completed>): VoiceSpec =
    VoiceSpec(name, audio, define(Chain(emptyList())).stages)

/** What a stage body may do while a run is in progress; also accumulates the [PipelineRun]. */
class StageScope internal constructor(
    private val events: MutableSharedFlow<PipelineEvent>,
    private val state: MutableStateFlow<PipelineState>,
    private val clock: TimeMark,
    internal val t0: Long,
) {
    internal var transcript: String = ""
    internal var outcome: NluOutcome? = null
    internal var correction: Correction? = null
    internal var action: ActionResult? = null
    internal var escalation: EscalationResult? = null
    internal var asrMs: Long = 0
    internal var nluMs: Long = 0
    internal var haltReason: String? = null

    fun now(): Long = clock.elapsedNow().inWholeMilliseconds

    fun emit(event: PipelineEvent) { events.tryEmit(event) }

    fun phase(next: PipelineState) { state.value = next }

    /** Stops the run at [stage]. Stage bodies return the result of this call. */
    fun fail(stage: Stage, reason: String): Nothing? {
        emit(PipelineEvent.Failed(now(), stage, reason))
        haltReason = reason
        return null
    }

    internal fun toRun(totalMs: Long): PipelineRun {
        val halted = haltReason
        return if (halted != null) PipelineRun("", null, null, null, null, asrMs, 0, totalMs, failure = halted)
        else PipelineRun(transcript, outcome, correction, action, escalation, asrMs, nluMs, totalMs,
            failure = (outcome as? NluOutcome.Failed)?.reason)
    }
}
