package sk.ainet.examples.moonshine.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sk.ainet.examples.moonshine.EngineOption
import sk.ainet.examples.moonshine.SpeechPlatform
import sk.ainet.examples.moonshine.Transcriber
import sk.ainet.examples.moonshine.Transcriber.Phase
import sk.ainet.examples.moonshine.UtteranceResult

private val Amber = Color(0xFFF2B544)
private val Colors = darkColorScheme(
    primary = Amber, onPrimary = Color(0xFF1B1405),
    background = Color(0xFF0F1216), onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF181C22), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF222831), onSurfaceVariant = Color(0xFF9AA3AF),
    outline = Color(0xFF39414D), error = Color(0xFFFF8A80),
)

/** The whole app, the same on every platform: give it the platform, it does the rest. */
@Composable
fun App(platform: SpeechPlatform) {
    val scope = rememberCoroutineScope()
    val transcriber = remember(platform) { Transcriber(platform, scope) }
    DisposableEffect(transcriber) { onDispose { transcriber.close() } }
    val state by transcriber.state.collectAsState()
    Screen(state, transcriber::select, transcriber::runSample, transcriber::toggleMicrophone, transcriber::refresh)
}

/** The screen for one [state]; stateless, so it can be rendered off-screen as well. */
@Composable
fun Screen(
    state: Transcriber.State,
    onSelect: (EngineOption) -> Unit = {},
    onSample: () -> Unit = {},
    onMicrophone: () -> Unit = {},
    onRescan: () -> Unit = {},
) {
    MaterialTheme(colorScheme = Colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text("Moonshine Anywhere", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Moonshine v2 streaming · SKaiNET cartridge · ${state.platform}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.engines.forEach { option ->
                            EngineCard(option, option == state.selected, !state.busy, Modifier.weight(1f)) { onSelect(option) }
                        }
                    }

                    val ready = state.selected?.available == true && !state.busy
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onSample, enabled = ready) { Text("Transcribe sample") }
                        if (state.hasMicrophone) {
                            val listening = state.phase == Phase.Listening
                            Button(
                                onClick = onMicrophone,
                                enabled = ready || listening,
                                colors = if (listening) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                            ) { Text(if (listening) "Stop" else "Listen") }
                        }
                        if (state.engines.any { !it.available }) {
                            OutlinedButton(onClick = onRescan, enabled = !state.busy) { Text("Rescan packs") }
                        }
                    }

                    Transcript(state)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    state.result?.let { Metrics(it, state.loadMs) }
                }
            }
        }
    }
}

@Composable
private fun EngineCard(option: EngineOption, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.heightIn(min = 104.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.surfaceVariant else scheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(option.device.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = if (option.available) scheme.onSurface else scheme.onSurfaceVariant)
            Text(option.runtime, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Text(
                option.unavailable ?: option.pack,
                style = MaterialTheme.typography.bodySmall,
                color = if (option.available) scheme.onSurfaceVariant else scheme.error,
            )
        }
    }
}

@Composable
private fun Transcript(state: Transcriber.State) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outline)) {
        Column(Modifier.padding(16.dp).heightIn(min = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (state.phase) {
                    Phase.Idle -> if (state.result != null) "Final transcript" else "Transcript"
                    Phase.Loading -> "Loading the cartridge…"
                    Phase.Listening -> "Listening (up to 5 s)…"
                    Phase.Sample -> "Playing the sample into the engine…"
                    Phase.Finishing -> "Final decode…"
                },
                style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant,
            )
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                state.partial.ifEmpty { if (state.busy) "…" else "Pick an engine, then transcribe the bundled sample or speak." },
                style = MaterialTheme.typography.headlineSmall,
                color = if (state.result != null) scheme.onSurface else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Metrics(result: UtteranceResult, loadMs: Long?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Metric("Real-time factor", formatRtf(result.rtf), if (result.rtf < 1) "keeps up with live audio" else "slower than live audio", Modifier.weight(1f))
        Metric("Final decode", "${result.finishMs} ms", "wait after the last word", Modifier.weight(1f))
        Metric("Load", loadMs?.let { "$it ms" } ?: "–", "once per engine", Modifier.weight(1f))
    }
    Text(
        "${result.audioMs} ms of audio · ${result.streamMs} ms in the engine while streaming · ${result.partials} partials" +
            (result.firstPartialAtMs?.let { ", first at $it ms" } ?: ""),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Metric(label: String, value: String, note: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outline)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, color = scheme.primary)
            Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Two decimals without String.format, which common code does not have. */
internal fun formatRtf(rtf: Double): String {
    val hundredths = (rtf * 100 + 0.5).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
