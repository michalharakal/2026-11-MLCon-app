package sk.ainet.examples.smarthome.distill

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import sk.ainet.cartridge.nlu.functiongemma.FunctionGemmaPrompt
import sk.ainet.cartridge.nlu.functiongemma.ToolCatalog
import sk.ainet.examples.smarthome.router.RouterLabels
import sk.ainet.examples.smarthome.tools.HomeTools
import java.io.File

/**
 * What the teacher scores, written as plain files so any teacher can be plugged in (`teacher/score.py` runs
 * FunctionGemma 270M; a larger model only has to produce the same `teacher.jsonl`).
 *
 * - `prompt.json`: the exact on-device prompt, rendered by the NLU blueprint's [FunctionGemmaPrompt] from the typed
 *   catalog, split at the utterance; plus, per router label, the text the model emits to call it.
 * - `phrases.jsonl`: every phrase with its split (`train`, `golden`, `paraphrase`) and intended label.
 *
 * The teacher answers with `teacher.jsonl`: one `{"text", "logprobs": [one per label]}` per phrase.
 */
object TeacherInputs {
    private val json = Json { ignoreUnknownKeys = true }

    fun write(dir: File) {
        dir.mkdirs()
        val catalog = json.decodeFromString(ToolCatalog.serializer(), HomeTools.catalog.toJson())
        val prompt = FunctionGemmaPrompt(catalog.definitions())
        val marker = "\u0000"
        val suffix = prompt.utteranceText(marker).substringAfter(marker)
        File(dir, "prompt.json").writeText(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
            put("catalog", HomeTools.catalog.id)
            put("bos", FunctionGemmaPrompt.BOS)
            put("prefix", prompt.prefixText)
            put("suffix", suffix)
            put("start_function_call", "<start_function_call>")
            put("labels", JsonArray(RouterLabels.all.map(::JsonPrimitive)))
            // what the device grammar matches after <start_function_call> (CallGrammar: "call:${name}{"); none = no call
            put("calls", JsonArray(RouterLabels.all.map { if (it == RouterLabels.NONE) JsonPrimitive(null as String?) else JsonPrimitive("call:$it{") }))
        }))
        File(dir, "phrases.jsonl").printWriter().use { w ->
            fun out(split: String, ps: List<Phrase>) = ps.forEach { p ->
                w.println(buildJsonObject { put("text", p.text); put("split", split); put("label", RouterLabels.all[p.label]) })
            }
            out("train", Phrases.generate()); out("golden", Phrases.heldOut); out("paraphrase", Paraphrases.cases)
        }
    }

    /** A teacher file → text to log-probabilities in [RouterLabels.all] order; empty if that teacher has not run. */
    fun readTeacher(f: File): Map<String, FloatArray> {
        if (!f.exists()) return emptyMap()
        return f.readLines().filter { it.isNotBlank() }.associate { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            val lp = o.getValue("logprobs").jsonArray.map { it.jsonPrimitive.floatOrNull ?: Float.NEGATIVE_INFINITY }.toFloatArray()
            require(lp.size == RouterLabels.all.size) { "teacher.jsonl: ${lp.size} scores, expected ${RouterLabels.all.size}" }
            o.getValue("text").jsonPrimitive.content to lp
        }
    }
}
