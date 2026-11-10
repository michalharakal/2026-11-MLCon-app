package sk.ainet.examples.smarthome

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import sk.ainet.examples.smarthome.actions.HomeActions
import sk.ainet.examples.smarthome.actions.HomeStore
import sk.ainet.examples.smarthome.engine.NluOutcome
import sk.ainet.examples.smarthome.fakes.FakeAsrEngine
import sk.ainet.examples.smarthome.fakes.KeywordNluEngine
import sk.ainet.examples.smarthome.hybrid.EscalationResult
import sk.ainet.examples.smarthome.hybrid.IntentEscalation
import sk.ainet.examples.smarthome.pipeline.PipelineEvent
import sk.ainet.examples.smarthome.pipeline.PipelineState
import sk.ainet.examples.smarthome.pipeline.Stage
import sk.ainet.examples.smarthome.pipeline.VoicePipeline
import sk.ainet.examples.smarthome.pipeline.homeVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineSpecTest {
    private val cloud = object : IntentEscalation {
        override suspend fun escalate(transcript: String, outcome: NluOutcome) = EscalationResult.NotHandled("none")
    }

    private fun spec(escalation: IntentEscalation = IntentEscalation.None) =
        homeVoice(FakeAsrEngine({ "turn on the kitchen light" }), KeywordNluEngine(), HomeActions(HomeStore()).router(), escalation)

    @Test
    fun `the home pipeline declares its stages in execution order`() {
        val spec = spec()
        assertEquals(listOf("asr", "nlu", "host-rules", "act"), spec.stages.map { it.id })
        assertEquals(16_000, spec.audio.sampleRateHz)
        assertEquals(1280, spec.audio.frameSamples)
    }

    @Test
    fun `panel chips come from the spec and show cloud only when escalation is declared`() {
        assertEquals(listOf(Stage.LISTENING, Stage.ASR, Stage.NLU, Stage.ACTION), spec().panelStages)
        assertTrue(Stage.CLOUD in spec(cloud).panelStages)
    }

    @Test
    fun `stages name the engine behind them`() {
        val spec = spec()
        assertEquals(FakeAsrEngine({ "" }).id, spec.stages.first { it.id == "asr" }.engineId)
        assertEquals(KeywordNluEngine().id, spec.stages.first { it.id == "nlu" }.engineId)
    }

    @Test
    fun `typed text skips only the speech stage`() = runTest {
        val direct = UnconfinedTestDispatcher(testScheduler)
        val spec = homeVoice(null, KeywordNluEngine(), HomeActions(HomeStore()).router(), asrDispatcher = direct, nluDispatcher = direct)
        val run = VoicePipeline(spec).runText("  turn on the kitchen light ")
        assertEquals("turn on the kitchen light", run.transcript)
        assertTrue(run.outcome is NluOutcome.Call, "$run")
        assertTrue(run.action!!.ok, "$run")
    }

    @Test
    fun `cancelling an utterance is not reported as a failure`() = runTest(UnconfinedTestDispatcher()) {
        val pipeline = VoicePipeline(
            homeVoice(
                FakeAsrEngine({ "x" }), KeywordNluEngine(), HomeActions(HomeStore()).router(),
                asrDispatcher = UnconfinedTestDispatcher(testScheduler),
            ),
        )
        val events = mutableListOf<PipelineEvent>()
        val collector = launch { pipeline.events.collect { events += it } }
        val never = CompletableDeferred<Unit>()
        val utterance = launch { pipeline.runUtterance(flow { emit(FloatArray(1280)); never.await() }) }

        utterance.cancelAndJoin()

        assertTrue(events.none { it is PipelineEvent.Failed }, "cancellation surfaced as $events")
        assertEquals(PipelineState.IDLE, pipeline.state.value)
        collector.cancel()
    }
}
