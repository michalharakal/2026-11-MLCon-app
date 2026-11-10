package sk.ainet.examples.smarthome

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import sk.ainet.examples.smarthome.actions.HomeActions
import sk.ainet.examples.smarthome.actions.HomeStore
import sk.ainet.examples.smarthome.cartridge.HomeCartridges
import sk.ainet.examples.smarthome.cartridges.AndroidEngineFactory
import sk.ainet.examples.smarthome.cartridges.CartridgeStore
import sk.ainet.examples.smarthome.cartridges.DevicePreference
import sk.ainet.examples.smarthome.engine.NluOutcome
import sk.ainet.examples.smarthome.pipeline.GoldenSet
import sk.ainet.examples.smarthome.pipeline.VoicePipeline
import java.io.File

/**
 * The on-device measurement run (D5/A7): loads the installed cartridges with the real runtimes, warms up, runs the
 * golden set through NLU + actions, and — when a text-to-speech engine is present — one spoken utterance through
 * the streaming ASR. Skips when no cartridges are installed. Results go to logcat (tag HESGolden) and to
 * `filesDir/golden-<device>.json`. Choose the IREE device with the instrumentation argument `device=gpu|cpu|auto`.
 */
@RunWith(AndroidJUnit4::class)
class RealCartridgeGoldenTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val device = when (args.getString("device", "auto")) { "gpu" -> DevicePreference.GPU; "cpu" -> DevicePreference.CPU; else -> DevicePreference.AUTO }
    private val store = CartridgeStore(Path(File(context.filesDir, "cartridges").absolutePath))
    /** `packs=<substring>` picks which installed packs to load when several targets are installed (e.g. `cpu-arm64`). */
    private val packFilter: String = args.getString("packs", "")
    /** `minPass=<n>` overrides the stage threshold. */
    private val minPass: Int = args.getString("minPass")?.toIntOrNull() ?: GoldenSet.PASS_THRESHOLD

    private fun log(s: String) = Log.i("HESGolden", s)
    private fun rssMb(): Long = Debug.getPss() / 1024
    private fun availMb(): Long { val mi = ActivityManager.MemoryInfo(); (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi); return mi.availMem / (1024 * 1024) }

    @Test
    fun goldenSetOnRealCartridges() = runBlocking {
        val installed = store.installed().filter { packFilter.isEmpty() || packFilter in it.id }
        val asrPack = installed.firstOrNull { HomeCartridges.Asr.conforms(it.descriptor) }
        val nluPack = installed.firstOrNull { HomeCartridges.Nlu.conforms(it.descriptor) }
        assumeTrue("no conforming cartridges installed in ${store.root}", asrPack != null && nluPack != null)
        val report = JSONObject().put("device", device.name).put("asr", asrPack!!.id).put("nlu", nluPack!!.id).put("rss_start_mb", rssMb()).put("avail_start_mb", availMb())
        val factory = AndroidEngineFactory(context)

        // the same check the app runs before loading: conformance, trusted signature, every artifact digest
        val checks = listOf(store.check(asrPack, HomeCartridges.Asr, HomeCartridges.trustedKeys), store.check(nluPack, HomeCartridges.Nlu, HomeCartridges.trustedKeys))
        for (c in checks) {
            log("check ${c.id} ${c.version} ${c.digest}: ${if (c.ok) "${c.signature.label}, ${c.artifacts.checked} artifacts ok" else "REFUSED ${c.problems}"} in ${c.checkMs} ms")
            report.put("check_${c.id}", JSONObject().put("digest", c.digest).put("signature", c.signature.label).put("artifacts", c.artifacts.checked).put("ok", c.ok).put("check_ms", c.checkMs).put("problems", JSONArray(c.problems)))
        }
        assertTrue("cartridge check failed: ${checks.filterNot { it.ok }.map { "${it.id}: ${it.problems}" }}", checks.all { it.ok })

        var t = System.nanoTime()
        val asr = factory.asr(asrPack, "en", device)
        asr.open()
        val asrLoadMs = (System.nanoTime() - t) / 1_000_000
        log("asr loaded in $asrLoadMs ms (${asrPack.id}, $device) rss=${rssMb()} MB")
        report.put("asr_load_ms", asrLoadMs).put("rss_after_asr_mb", rssMb())

        t = System.nanoTime()
        val nlu = factory.nlu(nluPack, device)
        nlu.warmUp()
        val nluWarmMs = (System.nanoTime() - t) / 1_000_000
        log("nlu warm in $nluWarmMs ms (${nluPack.id}, $device) rss=${rssMb()} MB")
        report.put("nlu_warmup_ms", nluWarmMs).put("rss_after_nlu_mb", rssMb()).put("avail_after_nlu_mb", availMb())

        val store = HomeStore()
        val pipeline = VoicePipeline(asr, nlu, HomeActions(store).router())
        val cases = JSONArray()
        var passed = 0
        var modelExact = 0
        val latencies = mutableListOf<Long>()
        for (c in GoldenSet.cases) {
            val run = pipeline.runText(c.utterance)
            val verdict = GoldenSet.judge(c, run)
            val ok = verdict.passed
            if (ok) passed++
            if (verdict.modelExact) modelExact++
            latencies += run.nluMs
            val o = run.outcome
            val raw = when (o) { is NluOutcome.Call -> o.raw; is NluOutcome.NoCall -> o.text; is NluOutcome.Failed -> "FAILED ${o.reason}"; null -> "" }
            val timing = (o as? NluOutcome.Call)?.timing ?: (o as? NluOutcome.NoCall)?.timing
            val acted = run.correction?.to ?: (o as? NluOutcome.Call)
            log("${if (ok) "PASS" else "FAIL"}${if (run.correction != null) " (host rule)" else ""} \"${c.utterance}\" -> ${run.calledTool ?: "no call"} ${acted?.args ?: ""}${if (verdict.problems.isEmpty()) "" else " !! ${verdict.problems.joinToString("; ")}"} | ${run.action?.message ?: ""} | nlu ${run.nluMs} ms ${timing?.let { "(tok ${it.tokenizeMs} restore ${it.restoreMs} chunk ${it.chunkMs} decode ${it.decodeMs}/${it.decodeTokens})" } ?: ""} | raw: ${raw.take(200)}")
            cases.put(JSONObject().put("utterance", c.utterance).put("expected", c.expectedTool).put("got", run.calledTool).put("args", JSONObject((o as? NluOutcome.Call)?.args ?: emptyMap<String, String>()))
                .put("acted_args", JSONObject(acted?.args ?: emptyMap<String, String>())).put("corrected", run.correction != null)
                .put("action", run.action?.message).put("ok", ok).put("model_exact", verdict.modelExact).put("problems", JSONArray(verdict.problems)).put("nlu_ms", run.nluMs).put("raw", raw.take(500))
                .put("timing", timing?.let { JSONObject().put("tokenize", it.tokenizeMs).put("restore", it.restoreMs).put("chunk", it.chunkMs).put("decode", it.decodeMs).put("decode_tokens", it.decodeTokens) }))
        }
        val sorted = latencies.sorted()
        report.put("cases", cases).put("passed", passed).put("model_exact", modelExact).put("threshold", minPass).put("total", GoldenSet.cases.size)
            .put("nlu_p50_ms", sorted[sorted.size / 2]).put("nlu_p95_ms", sorted[minOf(sorted.size - 1, (sorted.size * 95 + 99) / 100)])
            .put("rss_end_mb", rssMb()).put("avail_end_mb", availMb())
        log("golden: $passed/${GoldenSet.cases.size} (threshold $minPass, model alone $modelExact)  nlu p50 ${report.getLong("nlu_p50_ms")} ms  p95 ${report.getLong("nlu_p95_ms")} ms  rss ${rssMb()} MB")

        // spoken path, when the phone has a TTS voice: synthesize one command and stream it through the ASR
        SpokenUtterance.synthesize(context, "Turn on the kitchen light")?.let { pcm ->
            t = System.nanoTime()
            val session = asr.open()
            var partials = 0
            var i = 0
            while (i < pcm.size) { val n = minOf(1280, pcm.size - i); if (session.feed(pcm.copyOfRange(i, i + n)) != null) partials++; i += n }
            val text = session.finish()
            val asrMs = (System.nanoTime() - t) / 1_000_000
            log("asr (tts) \"$text\" in $asrMs ms for ${pcm.size * 1000 / 16000} ms of audio, $partials partials")
            val run = pipeline.runText(text)
            log("asr→nlu: ${run.calledTool} ${run.action?.message} nlu ${run.nluMs} ms")
            report.put("spoken", JSONObject().put("audio_ms", pcm.size * 1000 / 16000).put("asr_ms", asrMs).put("partials", partials).put("transcript", text).put("tool", run.calledTool).put("action", run.action?.message))
        } ?: log("no text-to-speech voice available; spoken path skipped")

        val out = File(context.filesDir, "golden-${nluPack.id}-${device.name.lowercase()}.json")
        out.writeText(report.toString(2))
        log("report: $out")
        nlu.close(); asr.close()
        assertTrue("golden set $passed/${GoldenSet.cases.size} is below the threshold $minPass", passed >= minPass)
    }
}
