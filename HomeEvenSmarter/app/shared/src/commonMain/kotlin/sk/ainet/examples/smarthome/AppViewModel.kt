package sk.ainet.examples.smarthome

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.ainet.examples.smarthome.actions.ActionResult
import sk.ainet.examples.smarthome.actions.HomeActions
import sk.ainet.examples.smarthome.actions.HomeStore
import sk.ainet.examples.smarthome.actions.Intent
import sk.ainet.examples.smarthome.cartridge.CartridgeExpectation
import sk.ainet.examples.smarthome.cartridge.CartridgeIndex
import sk.ainet.examples.smarthome.cartridge.CartridgeKinds
import sk.ainet.examples.smarthome.cartridge.HomeCartridges
import sk.ainet.examples.smarthome.cartridges.CartridgeCheck
import sk.ainet.examples.smarthome.cartridges.CartridgeDownloader
import sk.ainet.examples.smarthome.cartridges.CompanionToolClient
import sk.ainet.examples.smarthome.cartridges.DevicePreference
import sk.ainet.examples.smarthome.cartridges.DownloadEvent
import sk.ainet.examples.smarthome.cartridges.InstalledCartridge
import sk.ainet.examples.smarthome.engine.AsrEngine
import sk.ainet.examples.smarthome.engine.EngineStatus
import sk.ainet.examples.smarthome.engine.NluEngine
import sk.ainet.examples.smarthome.engine.NluOutcome
import sk.ainet.examples.smarthome.fakes.FakeAsrEngine
import sk.ainet.examples.smarthome.fakes.KeywordNluEngine
import sk.ainet.examples.smarthome.home.DeviceRef
import sk.ainet.examples.smarthome.home.HomeState
import sk.ainet.examples.smarthome.pipeline.GoldenCase
import sk.ainet.examples.smarthome.pipeline.GoldenSet
import sk.ainet.examples.smarthome.pipeline.GoldenVerdict
import sk.ainet.examples.smarthome.pipeline.PipelineEvent
import sk.ainet.examples.smarthome.pipeline.PipelineRun
import sk.ainet.examples.smarthome.pipeline.PipelineState
import sk.ainet.examples.smarthome.pipeline.Stage
import sk.ainet.examples.smarthome.pipeline.VoicePipeline
import sk.ainet.examples.smarthome.pipeline.homeVoice
import sk.ainet.examples.smarthome.tools.HomeTools
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** Engines as the UI sees them. */
data class EnginesState(
    val fakes: Boolean,
    val asr: EngineStatus = EngineStatus.Absent,
    val nlu: EngineStatus = EngineStatus.Absent,
    val device: DevicePreference = DevicePreference.AUTO,
    val asrId: String? = null,
    val nluId: String? = null,
    /** What was checked before each engine was loaded: conformance to the pipeline, signature, digests. */
    val asrCheck: CartridgeCheck? = null,
    val nluCheck: CartridgeCheck? = null,
) {
    val ready: Boolean get() = asr is EngineStatus.Ready && nlu is EngineStatus.Ready
}

data class CartridgesState(
    val installed: List<InstalledCartridge> = emptyList(),
    val index: CartridgeIndex? = null,
    val indexError: String? = null,
    val downloads: Map<String, DownloadEvent> = emptyMap(),
    val verification: Map<String, String> = emptyMap(),
)

/** One stage's status for the chips. */
enum class StageStatus { PENDING, ACTIVE, DONE, FAILED, SKIPPED }

/** The current (or last) run, as the pipeline panel draws it. */
data class RunView(
    val stages: Map<Stage, StageStatus> = Stage.entries.associateWith { StageStatus.PENDING },
    val source: String = "",
    val partial: String = "",
    val transcript: String = "",
    val audioMs: Long = 0,
    val asrMs: Long = 0,
    val outcome: NluOutcome? = null,
    val nluMs: Long = 0,
    val correction: String = "",
    val actionMessage: String = "",
    val actionOk: Boolean? = null,
    val escalation: String = "",
    val totalMs: Long = 0,
    val failure: String? = null,
    val level: Float = 0f,
)

data class GoldenResult(val case: GoldenCase, val run: PipelineRun) {
    val verdict: GoldenVerdict by lazy { GoldenSet.judge(case, run) }
    val passed: Boolean get() = verdict.passed
}

/**
 * The single owner of app state: engines, cartridges, the pipeline and what the UI shows. Platform entry points
 * create one per process (it survives configuration changes) and pass it into `App`.
 */
class AppViewModel(val env: AppEnvironment, private val scope: CoroutineScope, val homeStore: HomeStore = HomeStore()) {
    val homeState: StateFlow<HomeState> get() = homeStore.state

    private val _engines = MutableStateFlow(EnginesState(fakes = env.startWithFakes, device = runCatching { DevicePreference.valueOf(env.settings.devicePreference) }.getOrDefault(DevicePreference.AUTO)))
    val engines: StateFlow<EnginesState> = _engines.asStateFlow()

    private val _cartridges = MutableStateFlow(CartridgesState())
    val cartridges: StateFlow<CartridgesState> = _cartridges.asStateFlow()

    private val _run = MutableStateFlow(RunView())
    val run: StateFlow<RunView> = _run.asStateFlow()

    private val _pipelineState = MutableStateFlow(PipelineState.IDLE)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    private val _panelStages = MutableStateFlow(listOf(Stage.LISTENING, Stage.ASR, Stage.NLU, Stage.ACTION))
    /** The chips of the pipeline panel, as declared by the running pipeline's spec. */
    val panelStages: StateFlow<List<Stage>> = _panelStages.asStateFlow()

    private val _recentlyChanged = MutableStateFlow<Set<DeviceRef>>(emptySet())
    val recentlyChanged: StateFlow<Set<DeviceRef>> = _recentlyChanged.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _golden = MutableStateFlow<List<GoldenResult>>(emptyList())
    val golden: StateFlow<List<GoldenResult>> = _golden.asStateFlow()

    private val _serverUrl = MutableStateFlow(env.settings.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private var asr: AsrEngine? = null
    private var nlu: NluEngine? = null
    private var pipeline: VoicePipeline? = null
    private var pipelineJob: Job? = null
    private var frames: Channel<FloatArray>? = null
    private var captureJob: Job? = null
    private var utteranceJob: Job? = null
    private var fakeScriptIndex = 0
    private val downloader = CartridgeDownloader(env.store)
    private val toolClient = CompanionToolClient()
    private val started = TimeSource.Monotonic.markNow()

    init {
        refreshCartridges()
        if (env.startWithFakes) useFakes(true)
    }

    // ---- engines -------------------------------------------------------------------------------------------

    fun useFakes(on: Boolean) {
        closeEngines()
        _engines.update { it.copy(fakes = on, asr = EngineStatus.Absent, nlu = EngineStatus.Absent, asrId = null, nluId = null) }
        if (on) {
            asr = FakeAsrEngine({ GoldenSet.cases[fakeScriptIndex++ % GoldenSet.cases.size].utterance })
            nlu = KeywordNluEngine(simulatedLatencyMs = 400)
            _engines.update { it.copy(asr = EngineStatus.Ready(0, "scripted partials"), nlu = EngineStatus.Ready(0, "keyword rules"), asrId = asr!!.id, nluId = nlu!!.id) }
            rebuildPipeline()
            log("engines: fakes")
        }
    }

    fun setDevice(pref: DevicePreference) {
        env.settings.devicePreference = pref.name
        _engines.update { it.copy(device = pref) }
    }

    /**
     * Loads the cartridges the pipeline declares ([HomeCartridges]) into real engines and warms them up. Each pack is
     * selected by conformance, then checked — signature by a trusted key, every artifact digest — and refused if any
     * check fails. Long: seconds for the check, tens of seconds for the NLU warm-up.
     */
    fun loadAndWarmUp() {
        if (!env.engineFactory.available) { log("no runtime on ${env.platform}; staying on fakes"); return }
        val preferCpu = _engines.value.device == DevicePreference.CPU
        val asrPack = env.store.select(HomeCartridges.Asr, preferCpu); val nluPack = env.store.select(HomeCartridges.Nlu, preferCpu)
        if (asrPack == null || nluPack == null) {
            log("no conforming cartridges: asr=${asrPack?.id} nlu=${nluPack?.id}")
            for ((id, problems) in env.store.rejected(HomeCartridges.Asr) + env.store.rejected(HomeCartridges.Nlu)) log("  $id: ${problems.joinToString("; ")}")
            return
        }
        closeEngines()
        _engines.update { it.copy(fakes = false, asr = EngineStatus.Loading("checking signature + digests", 0), nlu = EngineStatus.Loading("checking signature + digests", 0), asrId = asrPack.id, nluId = nluPack.id, asrCheck = null, nluCheck = null) }
        scope.launch(Dispatchers.Default) {
            val device = _engines.value.device
            val t0 = started.elapsedNow().inWholeMilliseconds
            val ticker = launch { while (true) { delay(250); val e = started.elapsedNow().inWholeMilliseconds - t0; _engines.update { s -> s.copy(asr = (s.asr as? EngineStatus.Loading)?.copy(elapsedMs = e) ?: s.asr, nlu = (s.nlu as? EngineStatus.Loading)?.copy(elapsedMs = e) ?: s.nlu) } } }
            try {
                val asrCheck = env.store.check(asrPack, HomeCartridges.Asr, HomeCartridges.trustedKeys)
                _engines.update { it.copy(asrCheck = asrCheck) }
                log(checkLine(asrCheck))
                val nluCheck = env.store.check(nluPack, HomeCartridges.Nlu, HomeCartridges.trustedKeys)
                _engines.update { it.copy(nluCheck = nluCheck) }
                log(checkLine(nluCheck))
                val refused = listOf(asrCheck, nluCheck).filterNot { it.ok }
                if (refused.isNotEmpty()) {
                    val reason = "refused ${refused.joinToString { "${it.id}: ${it.problems.first()}" }}"
                    _engines.update { it.copy(asr = if (asrCheck.ok) EngineStatus.Absent else EngineStatus.Failed(reason), nlu = if (nluCheck.ok) EngineStatus.Absent else EngineStatus.Failed(reason)) }
                    log(reason)
                    return@launch
                }
                _engines.update { it.copy(asr = EngineStatus.Loading("opening", started.elapsedNow().inWholeMilliseconds - t0), nlu = EngineStatus.Loading("waiting", started.elapsedNow().inWholeMilliseconds - t0)) }
                val a = env.engineFactory.asr(asrPack, "en", device)
                a.open() // loads the five graphs
                asr = a
                val asrMs = started.elapsedNow().inWholeMilliseconds - t0
                _engines.update { it.copy(asr = EngineStatus.Ready(asrMs, "${asrPack.descriptor.target.accelerator ?: "cpu"}"), nlu = EngineStatus.Loading("warm-up: catalog prefill", asrMs)) }
                log("asr ready in $asrMs ms (${asrPack.id})")
                val t1 = started.elapsedNow().inWholeMilliseconds
                val n = env.engineFactory.nlu(nluPack, device)
                n.warmUp()
                nlu = n
                val nluMs = started.elapsedNow().inWholeMilliseconds - t1
                _engines.update { it.copy(nlu = EngineStatus.Ready(nluMs, "${nluPack.descriptor.target.accelerator ?: "cpu"}")) }
                log("nlu ready in $nluMs ms (${nluPack.id})")
                rebuildPipeline()
            } catch (e: Throwable) {
                val reason = e.message ?: e::class.simpleName ?: "failed"
                _engines.update { s -> s.copy(asr = if (asr == null) EngineStatus.Failed(reason) else s.asr, nlu = if (nlu == null) EngineStatus.Failed(reason) else s.nlu) }
                log("engine load failed: $reason")
            } finally { ticker.cancel() }
        }
    }

    private fun rebuildPipeline() {
        val n = nlu ?: return
        pipelineJob?.cancel()
        // the home commands act locally; remote tools go to the companion middleware
        val router = HomeActions(homeStore).router().register(HomeTools.GET_WEATHER, ::remoteTool)
        val spec = homeVoice(asr, n, router)
        val p = VoicePipeline(spec)
        pipeline = p
        _panelStages.value = spec.panelStages
        pipelineJob = scope.launch {
            launch { p.state.collect { _pipelineState.value = it } }
            p.events.collect(::onEvent)
        }
    }

    /** Executes a remote tool on the companion server. A dead companion is a failed action, never a crash. */
    private suspend fun remoteTool(intent: Intent): ActionResult = try {
        val result = toolClient.call(_serverUrl.value, intent.tool, intent.args)
        ActionResult(intent.tool, result.ok, result.message)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ActionResult(intent.tool, false, "companion unreachable — start the companion server and check its URL")
    }

    private fun closeEngines() {
        stopListening()
        pipelineJob?.cancel(); pipelineJob = null; pipeline = null
        runCatching { asr?.close() }; runCatching { nlu?.close() }
        asr = null; nlu = null
    }

    // ---- talking -------------------------------------------------------------------------------------------

    val canTalk: Boolean get() = pipeline != null && asr != null && env.audioSource != null && _pipelineState.value == PipelineState.IDLE

    /** Push-to-talk: press. Frames flow from the microphone into the pipeline until [stopListening]. */
    fun startListening() {
        val p = pipeline ?: return
        val source = env.audioSource ?: return
        if (_pipelineState.value != PipelineState.IDLE) return
        val channel = Channel<FloatArray>(capacity = 64)
        frames = channel
        captureJob = scope.launch(Dispatchers.Default) {
            try { source.frames().collect { channel.send(it) } } catch (_: Exception) {} finally { channel.close() }
        }
        utteranceJob = scope.launch {
            val run = p.runUtterance(channel.consumeAsFlow(), source = "mic")
            log(runLine(run))
        }
    }

    /** Push-to-talk: release. */
    fun stopListening() {
        captureJob?.cancel(); captureJob = null
        frames?.close(); frames = null
    }

    fun runText(text: String) {
        val p = pipeline ?: return
        if (text.isBlank()) return
        scope.launch { log(runLine(p.runText(text))) }
    }

    fun runGoldenSet() {
        val p = pipeline ?: return
        scope.launch {
            _golden.value = emptyList()
            for (case in GoldenSet.cases) {
                val run = p.runText(case.utterance)
                _golden.update { it + GoldenResult(case, run) }
            }
            val results = _golden.value
            log("golden set: ${results.count { it.passed }}/${results.size} passed, nlu p50 ${median(results.map { it.run.nluMs })} ms")
        }
    }

    fun resetHome() { homeStore.reset(); log("home reset") }

    private fun runLine(run: PipelineRun): String = buildString {
        append('"').append(run.transcript).append('"')
        append(" → ").append(run.calledTool ?: (run.outcome as? NluOutcome.NoCall)?.let { "no call" } ?: run.failure ?: "?")
        run.action?.let { append(" → ").append(it.message) }
        append(" [asr ${run.asrMs} ms, nlu ${run.nluMs} ms]")
    }

    private fun onEvent(e: PipelineEvent) {
        _run.update { v ->
            when (e) {
                is PipelineEvent.Started -> RunView(source = e.source).withStage(Stage.LISTENING, StageStatus.ACTIVE)
                is PipelineEvent.Level -> v.copy(level = e.rms)
                is PipelineEvent.Partial -> v.copy(partial = e.text).withStage(Stage.ASR, StageStatus.ACTIVE)
                is PipelineEvent.Transcribed -> v.copy(transcript = e.text, partial = e.text, audioMs = e.audioMs, asrMs = e.asrMs, level = 0f)
                    .withStage(Stage.LISTENING, StageStatus.DONE).withStage(Stage.ASR, if (e.source() == "text") StageStatus.SKIPPED else StageStatus.DONE)
                is PipelineEvent.Resolving -> v.withStage(Stage.NLU, StageStatus.ACTIVE)
                is PipelineEvent.Resolved -> v.copy(outcome = e.outcome, nluMs = e.nluMs).withStage(Stage.NLU, if (e.outcome is NluOutcome.Failed) StageStatus.FAILED else StageStatus.DONE)
                is PipelineEvent.Corrected -> v.copy(correction = "rule: ${e.correction.reason} → ${e.correction.to.name}(${e.correction.to.args.entries.joinToString { "${it.key}=\"${it.value}\"" }})")
                is PipelineEvent.Acted -> {
                    if (e.result.ok) flash(e.result.changed)
                    val acted = v.copy(actionMessage = e.result.message, actionOk = e.result.ok).withStage(Stage.ACTION, if (e.result.ok) StageStatus.DONE else StageStatus.FAILED)
                    // a remote tool ran on the companion: the cloud chip is no placeholder for that run
                    if (e.result.tool in HomeTools.homeCommandNames) acted
                    else acted.withStage(Stage.CLOUD, if (e.result.ok) StageStatus.DONE else StageStatus.FAILED)
                }
                is PipelineEvent.Escalated -> v.copy(escalation = "${e.reason} → ${e.result::class.simpleName}: ${e.result.describe()}")
                    .withStage(Stage.ACTION, if (v.stages[Stage.ACTION] == StageStatus.PENDING) StageStatus.SKIPPED else v.stages.getValue(Stage.ACTION))
                    .withStage(Stage.CLOUD, StageStatus.SKIPPED)
                is PipelineEvent.Failed -> v.copy(failure = e.reason, level = 0f).withStage(e.stage, StageStatus.FAILED)
                is PipelineEvent.Finished -> v.copy(totalMs = e.totalMs, level = 0f)
            }
        }
    }

    private fun PipelineEvent.Transcribed.source(): String = _run.value.source

    private fun sk.ainet.examples.smarthome.hybrid.EscalationResult.describe(): String = when (this) {
        is sk.ainet.examples.smarthome.hybrid.EscalationResult.Handled -> "$message ($source)"
        is sk.ainet.examples.smarthome.hybrid.EscalationResult.NotHandled -> reason
    }

    private fun RunView.withStage(stage: Stage, status: StageStatus) = copy(stages = stages + (stage to status))

    private fun flash(devices: Set<DeviceRef>) {
        if (devices.isEmpty()) return
        _recentlyChanged.update { it + devices }
        scope.launch { delay(900); _recentlyChanged.update { it - devices } }
    }

    // ---- cartridges ----------------------------------------------------------------------------------------

    fun refreshCartridges() {
        scope.launch(Dispatchers.Default) {
            val installed = env.store.installed()
            _cartridges.update { it.copy(installed = installed) }
        }
    }

    fun setServerUrl(url: String) { _serverUrl.value = url; env.settings.serverUrl = url }

    fun fetchIndex() {
        scope.launch {
            _cartridges.update { it.copy(indexError = null) }
            try {
                val index = withContext(Dispatchers.Default) { downloader.index(_serverUrl.value) }
                _cartridges.update { it.copy(index = index) }
                log("server lists ${index.cartridges.size} cartridges")
                runCatching { withContext(Dispatchers.Default) { toolClient.tools(_serverUrl.value) } }
                    .onSuccess { tools -> log("companion tools: " + tools.tools.map { it.name }.ifEmpty { listOf("none") }.joinToString()) }
            } catch (e: Exception) {
                _cartridges.update { it.copy(indexError = e.message ?: "cannot reach server") }
            }
        }
    }

    fun download(entry: CartridgeIndex.Entry) {
        scope.launch(Dispatchers.Default) {
            downloader.download(_serverUrl.value, entry).collect { ev ->
                _cartridges.update { it.copy(downloads = it.downloads + (entry.id to ev)) }
                if (ev is DownloadEvent.Done) { log("downloaded ${entry.id} (${ev.bytes / 1_000_000} MB)"); refreshCartridges() }
                if (ev is DownloadEvent.Failed) log("download failed ${entry.id}: ${ev.reason}")
            }
        }
    }

    fun verify(cartridge: InstalledCartridge) {
        scope.launch(Dispatchers.Default) {
            _cartridges.update { it.copy(verification = it.verification + (cartridge.id to "verifying…")) }
            val c = env.store.check(cartridge, expectationFor(cartridge.task), HomeCartridges.trustedKeys)
            val text = if (c.ok) "${c.signature.label} · ${c.artifacts.checked} artifacts verified · ${c.shortDigest} · ${c.checkMs} ms" else c.problems.joinToString("; ")
            _cartridges.update { it.copy(verification = it.verification + (cartridge.id to text)) }
            log("${cartridge.id}: $text")
        }
    }

    fun delete(cartridge: InstalledCartridge) {
        scope.launch(Dispatchers.Default) { env.store.delete(cartridge.id); refreshCartridges(); log("deleted ${cartridge.id}") }
    }

    /**
     * Tooling hook (store screenshots, previews): replaces the observable state wholesale with a hand-picked demo
     * state, without touching engines, the pipeline or disk. The running app never calls this.
     */
    fun presentDemo(
        engines: EnginesState? = null,
        cartridges: CartridgesState? = null,
        run: RunView? = null,
        log: List<String>? = null,
        golden: List<GoldenResult>? = null,
        recentlyChanged: Set<DeviceRef>? = null,
    ) {
        engines?.let { _engines.value = it }
        cartridges?.let { _cartridges.value = it }
        run?.let { _run.value = it }
        log?.let { _log.value = it }
        golden?.let { _golden.value = it }
        recentlyChanged?.let { _recentlyChanged.value = it }
    }

    private fun log(line: String) {
        val t = started.elapsedNow().inWholeMilliseconds
        _log.update { (it + "${(t / 1000).toString().padStart(4)}s  $line").takeLast(200) }
    }

    private fun expectationFor(task: String): CartridgeExpectation? = when (task) {
        CartridgeKinds.TASK_ASR -> HomeCartridges.Asr
        CartridgeKinds.TASK_NLU -> HomeCartridges.Nlu
        else -> null
    }

    private fun checkLine(c: CartridgeCheck): String =
        "${c.id} ${c.version} ${c.shortDigest}: " + (if (c.ok) "${c.signature.label}, ${c.artifacts.checked} artifacts ok" else "REFUSED ${c.problems.joinToString("; ")}") + " (${c.checkMs} ms)"

    private fun median(values: List<Long>): Long = if (values.isEmpty()) 0 else values.sorted()[values.size / 2]
}
