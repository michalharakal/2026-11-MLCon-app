package sk.ainet.examples.smarthome.pipeline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import sk.ainet.examples.smarthome.actions.ActionRouter
import sk.ainet.examples.smarthome.engine.AsrEngine
import sk.ainet.examples.smarthome.engine.NluEngine
import sk.ainet.examples.smarthome.hybrid.IntentEscalation

/** The smart-home voice pipeline. This declaration is what runs and what the pipeline panel draws. */
fun homeVoice(
    asr: AsrEngine?,
    nlu: NluEngine,
    router: ActionRouter,
    escalation: IntentEscalation = IntentEscalation.None,
    nluBudgetMs: Long = 15_000,
    asrDispatcher: CoroutineDispatcher = Dispatchers.Default,
    nluDispatcher: CoroutineDispatcher = Dispatchers.Default,
): VoiceSpec = voicePipeline("home-voice", AudioFormat(sampleRateHz = 16_000, frameMs = 80)) { audio ->
    audio then
        speechToText(asr, asrDispatcher) then
        understand(nlu, nluBudgetMs, nluDispatcher) then
        hostRules() then
        act(router, escalation)
}
