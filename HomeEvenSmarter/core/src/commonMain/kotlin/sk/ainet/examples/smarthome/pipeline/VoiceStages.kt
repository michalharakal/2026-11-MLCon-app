package sk.ainet.examples.smarthome.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import sk.ainet.examples.smarthome.actions.ActionResult
import sk.ainet.examples.smarthome.actions.ActionRouter
import sk.ainet.examples.smarthome.actions.Intent
import sk.ainet.examples.smarthome.engine.AsrEngine
import sk.ainet.examples.smarthome.engine.NluEngine
import sk.ainet.examples.smarthome.engine.NluOutcome
import sk.ainet.examples.smarthome.hybrid.EscalationResult
import sk.ainet.examples.smarthome.hybrid.IntentEscalation
import sk.ainet.examples.smarthome.tools.HostRules

/** Streaming speech-to-text: partials while frames arrive, one exact transcript when they complete. */
fun speechToText(engine: AsrEngine?, dispatcher: CoroutineDispatcher = Dispatchers.Default): VoiceStage<Utterance, Transcript> =
    VoiceStage("asr", listOf(Stage.ASR), engine?.id) body@{ utterance ->
        val asr = engine ?: return@body fail(Stage.ASR, "no speech engine")
        emit(PipelineEvent.Started(t0, utterance.source))
        phase(PipelineState.LISTENING)
        var samples = 0L
        val text = try {
            val session = asr.open()
            withContext(dispatcher) {
                utterance.frames.collect { frame ->
                    samples += frame.size
                    emit(PipelineEvent.Level(now(), VoicePipeline.rms(frame)))
                    session.feed(frame)?.let { emit(PipelineEvent.Partial(now(), it)) }
                }
            }
            phase(PipelineState.TRANSCRIBING)
            withContext(dispatcher) { session.finish() }.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@body fail(Stage.ASR, e.message ?: e::class.simpleName ?: "asr failed")
        }
        asrMs = now() - t0
        emit(PipelineEvent.Transcribed(now(), text, samples * 1000 / asr.sampleRateHz, asrMs))
        if (text.isEmpty()) return@body fail(Stage.ASR, "nothing recognized")
        Transcript(text)
    }

/** One transcript to one [NluOutcome], within [budgetMs]. A failure is an outcome, so the run continues. */
fun understand(
    engine: NluEngine,
    budgetMs: Long = 15_000,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): VoiceStage<Transcript, Understood> = VoiceStage("nlu", listOf(Stage.NLU), engine.id) { input ->
    transcript = input.text
    phase(PipelineState.RESOLVING)
    emit(PipelineEvent.Resolving(now(), input.text))
    val started = now()
    val hardLimitMs = budgetMs + 5_000
    val result: NluOutcome = try {
        withTimeout(hardLimitMs) { withContext(dispatcher) { engine.resolve(input.text, budgetMs) } }
    } catch (e: TimeoutCancellationException) {
        NluOutcome.Failed("nlu exceeded $hardLimitMs ms")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        NluOutcome.Failed(e.message ?: e::class.simpleName ?: "nlu failed", e)
    }
    nluMs = now() - started
    outcome = result
    emit(PipelineEvent.Resolved(now(), result, nluMs))
    Understood(input.text, result)
}

/** Deterministic host repairs of the model's call, before anything is acted on. */
fun hostRules(): VoiceStage<Understood, Decision> = VoiceStage("host-rules", emptyList(), null) { input ->
    val call = input.outcome as? NluOutcome.Call
    correction = call?.let { HostRules.apply(input.transcript, it) }?.also { emit(PipelineEvent.Corrected(now(), it)) }
    Decision(input, correction?.to ?: call)
}

/** Acts on the decision through [router]. Anything that could not be done locally goes to [escalation]. */
fun act(
    router: ActionRouter,
    escalation: IntentEscalation = IntentEscalation.None,
): VoiceStage<Decision, Completed> {
    val chips = if (escalation === IntentEscalation.None) listOf(Stage.ACTION) else listOf(Stage.ACTION, Stage.CLOUD)
    return VoiceStage("act", chips, null) { decision ->
        val understood = decision.understood
        val call = decision.call
        suspend fun StageScope.escalateTo(reason: String): EscalationResult {
            val result = try {
                escalation.escalate(understood.transcript, understood.outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EscalationResult.NotHandled(e.message ?: "escalation failed")
            }
            emit(PipelineEvent.Escalated(now(), reason, result))
            return result
        }

        var done: ActionResult? = null
        var escalated: EscalationResult? = null
        if (call != null) {
            phase(PipelineState.ACTING)
            done = router.dispatch(Intent(call.name, call.args))
            emit(PipelineEvent.Acted(now(), done))
            if (!done.ok) escalated = escalateTo("action rejected: ${done.message}")
        } else {
            val reason = (understood.outcome as? NluOutcome.Failed)?.reason ?: "no function call"
            escalated = escalateTo(reason)
        }
        action = done
        this.escalation = escalated
        Completed(done, escalated)
    }
}
