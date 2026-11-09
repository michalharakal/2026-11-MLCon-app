package sk.ainet.examples.smarthome.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.ainet.examples.smarthome.AppViewModel
import sk.ainet.examples.smarthome.RunView
import sk.ainet.examples.smarthome.StageStatus
import sk.ainet.examples.smarthome.engine.NluOutcome
import sk.ainet.examples.smarthome.pipeline.PipelineState
import sk.ainet.examples.smarthome.pipeline.Stage

/** Live view of the pipeline: stage chips, partial and final transcript, NLU timing bars, raw model output, action. */
@Composable
fun PipelinePanel(vm: AppViewModel, modifier: Modifier = Modifier) {
    val run by vm.run.collectAsState()
    val state by vm.pipelineState.collectAsState()
    val engines by vm.engines.collectAsState()
    val log by vm.log.collectAsState()
    val stages by vm.panelStages.collectAsState()
    Column(modifier) {
        Text("Pipeline", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        StageChips(run, stages)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Speech", Palette.accent) {
                val text = if (run.transcript.isNotEmpty()) run.transcript else run.partial
                Text(
                    text.ifEmpty { if (state == PipelineState.LISTENING) "listening…" else "—" },
                    fontSize = 22.sp, fontFamily = FontFamily.Monospace,
                    color = if (run.transcript.isNotEmpty()) Palette.text else Palette.textDim,
                )
                if (run.asrMs > 0) Meta("audio ${run.audioMs} ms · asr ${run.asrMs} ms · ${engines.asrId ?: ""}")
            }
            Section("Understanding", Palette.accent2) {
                when (val o = run.outcome) {
                    null -> Text(if (state == PipelineState.RESOLVING) "thinking…" else "—", color = Palette.textDim)
                    is NluOutcome.Call -> {
                        Text("${o.name}(${o.args.entries.joinToString { "${it.key}=\"${it.value}\"" }})", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Palette.accent2)
                        if (o.raw.isNotEmpty() && o.raw != o.name) Meta("raw: ${o.raw.take(160)}")
                        TimingBars(o.timing)
                    }
                    is NluOutcome.NoCall -> { Text("no function call", color = Palette.warn); Meta(o.text.take(200)); TimingBars(o.timing) }
                    is NluOutcome.Failed -> { Text("failed: ${o.reason}", color = Palette.error); o.timing?.let { TimingBars(it) } }
                }
                if (run.correction.isNotEmpty()) Text(run.correction, color = Palette.warn, fontSize = 13.sp)
                if (run.nluMs > 0) Meta("nlu ${run.nluMs} ms · ${engines.nluId ?: ""}")
            }
            Section("Action", Palette.ok) {
                Text(run.actionMessage.ifEmpty { "—" }, color = when (run.actionOk) { true -> Palette.ok; false -> Palette.error; null -> Palette.textDim }, fontSize = 18.sp)
                if (run.escalation.isNotEmpty()) Meta("escalation: ${run.escalation}", color = Palette.cloud)
                run.failure?.let { Meta("failure: $it", color = Palette.error) }
                if (run.totalMs > 0) Meta("total ${run.totalMs} ms")
            }
            TypedInput(vm, enabled = state == PipelineState.IDLE && (engines.ready))
            Section("Log", Palette.textDim) {
                for (line in log.takeLast(8).reversed()) Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.textDim)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TalkButton(vm) }
    }
}

/** Portrait companion of [PipelinePanel]: stage chips, the current sentence, the outcome, and the talk button. */
@Composable
fun PipelineStrip(vm: AppViewModel, modifier: Modifier = Modifier) {
    val run by vm.run.collectAsState()
    val state by vm.pipelineState.collectAsState()
    val stages by vm.panelStages.collectAsState()
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StageChips(run, stages)
        val text = if (run.transcript.isNotEmpty()) run.transcript else run.partial
        Text(
            text.ifEmpty { if (state == PipelineState.LISTENING) "listening…" else "hold the button and speak" },
            fontSize = 16.sp, fontFamily = FontFamily.Monospace, maxLines = 2,
            color = if (run.transcript.isNotEmpty()) Palette.text else Palette.textDim,
        )
        val outcome = when (val o = run.outcome) {
            null -> if (state == PipelineState.RESOLVING) "thinking…" else ""
            is NluOutcome.Call -> "${o.name}(${o.args.entries.joinToString { "${it.key}=${it.value}" }})"
            is NluOutcome.NoCall -> "no function call"
            is NluOutcome.Failed -> "failed: ${o.reason}"
        }
        if (outcome.isNotEmpty()) Text(outcome, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Palette.accent2, maxLines = 2)
        if (run.correction.isNotEmpty()) Text(run.correction, fontSize = 12.sp, color = Palette.warn, maxLines = 2)
        if (run.actionMessage.isNotEmpty()) Text(run.actionMessage + (if (run.totalMs > 0) "  ·  ${run.totalMs} ms" else ""), fontSize = 14.sp, color = if (run.actionOk == false) Palette.error else Palette.ok, maxLines = 2)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TalkButton(vm) }
    }
}

@Composable
private fun StageChips(run: RunView, stages: List<Stage>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (stage in stages) {
            val status = run.stages[stage] ?: StageStatus.PENDING
            val target = when (status) {
                StageStatus.PENDING -> Palette.surfaceHigh
                StageStatus.ACTIVE -> when (stage) { Stage.LISTENING, Stage.ASR -> Palette.accent; Stage.NLU -> Palette.accent2; Stage.ACTION -> Palette.ok; Stage.CLOUD -> Palette.cloud }
                StageStatus.DONE -> Palette.ok.copy(alpha = 0.75f)
                StageStatus.FAILED -> Palette.error
                StageStatus.SKIPPED -> Palette.outline
            }
            val color by animateColorAsState(target)
            val label = when (stage) { Stage.LISTENING -> "mic"; Stage.ASR -> "ASR"; Stage.NLU -> "NLU"; Stage.ACTION -> "act"; Stage.CLOUD -> "cloud" }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(color).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.sp, color = if (status == StageStatus.PENDING) Palette.textDim else Palette.background, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Section(title: String, color: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(12.dp)) {
        Text(title.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Meta(text: String, color: Color = Palette.textDim) { Text(text, color = color, fontSize = 12.sp) }

@Composable
private fun TimingBars(t: NluOutcome.Timing) {
    val total = t.totalMs.coerceAtLeast(1)
    val parts = listOf("tokenize" to t.tokenizeMs, "restore" to t.restoreMs, "chunk" to t.chunkMs, "decode ${t.decodeTokens} tok" to t.decodeMs)
    val colors = listOf(Palette.textDim, Palette.accent, Palette.accent2, Palette.ok)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        parts.forEachIndexed { i, (_, ms) -> if (ms > 0) Box(Modifier.weight(ms.toFloat() / total).fillMaxHeight().background(colors[i])) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        parts.forEachIndexed { i, (name, ms) -> if (ms > 0) Text("$name ${ms} ms", fontSize = 11.sp, color = colors[i]) }
    }
}

@Composable
private fun TypedInput(vm: AppViewModel, enabled: Boolean) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("type a command instead of speaking") }, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Button(onClick = { vm.runText(text); text = "" }, enabled = enabled && text.isNotBlank()) { Text("Run") }
    }
}
