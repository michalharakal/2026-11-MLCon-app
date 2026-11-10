package sk.ainet.examples.smarthome.pipeline

import sk.ainet.examples.smarthome.engine.NluOutcome
import sk.ainet.examples.smarthome.tools.HomeTools
import sk.ainet.examples.smarthome.tools.HomeTools.Args

/**
 * One rehearsed utterance, the function it must resolve to (`null` = must not call a home function) and the
 * arguments the acted call must carry. An argument mapped to `null` must be absent (the whole home has no room).
 * [remote] marks a tool the companion middleware executes: the case scores on the NLU hit alone, so the
 * golden set keeps separating understanding errors from infrastructure (no companion reachable).
 */
data class GoldenCase(
    val utterance: String,
    val expectedTool: String?,
    val expectedArgs: Map<String, String?> = emptyMap(),
    val remote: Boolean = false,
)

/**
 * How one run scored. [passed] judges the whole pipeline (model, host rules, action); [modelExact] says whether the
 * language model alone already produced the right function, before any host rule rewrote it.
 */
data class GoldenVerdict(val passed: Boolean, val modelExact: Boolean, val problems: List<String>)

/**
 * The utterances spoken on stage, also the typed regression set: run them through the NLU alone to separate
 * recognition errors from understanding errors. Eleven in-catalog (one of them a remote tool the companion
 * executes), one deliberately outside it (it must land on the escalation seam, not on a wrong function).
 */
object GoldenSet {
    val cases: List<GoldenCase> = listOf(
        GoldenCase("Turn on the kitchen light", HomeTools.SET_LIGHT, mapOf(Args.ROOM to "kitchen", Args.STATE to "on")),
        GoldenCase("Switch off the bedroom light", HomeTools.SET_LIGHT, mapOf(Args.ROOM to "bedroom", Args.STATE to "off")),
        GoldenCase("Dim the living room light to twenty percent", HomeTools.SET_LIGHT, mapOf(Args.ROOM to "living_room", Args.BRIGHTNESS to "20")),
        GoldenCase("Set the bedroom to twenty one degrees", HomeTools.SET_THERMOSTAT, mapOf(Args.ROOM to "bedroom", Args.TEMPERATURE to "21")),
        GoldenCase("Make it nineteen degrees in the whole house", HomeTools.SET_THERMOSTAT, mapOf(Args.ROOM to null, Args.TEMPERATURE to "19")),
        GoldenCase("Close the living room blinds", HomeTools.SET_BLINDS, mapOf(Args.ROOM to "living_room", Args.POSITION to "closed")),
        GoldenCase("Open the kitchen blinds halfway", HomeTools.SET_BLINDS, mapOf(Args.ROOM to "kitchen", Args.POSITION to "half")),
        GoldenCase("Lock the front door", HomeTools.SET_LOCK, mapOf(Args.DOOR to "front_door", Args.STATE to "locked")),
        GoldenCase("Movie time", HomeTools.SET_SCENE, mapOf(Args.SCENE to "movie")),
        GoldenCase("What is the status of the hallway", HomeTools.GET_STATUS, mapOf(Args.ROOM to "hallway")),
        GoldenCase("What is the weather like tomorrow", HomeTools.GET_WEATHER, mapOf(Args.WHEN to "tomorrow"), remote = true),
        GoldenCase("Order a pizza for dinner", null),
    )

    /** The minimum the demo pipeline must score on the phone before it goes on stage. */
    const val PASS_THRESHOLD = 10

    /** The one pass rule, shared by the in-app golden screen and the on-device test. */
    fun judge(case: GoldenCase, run: PipelineRun): GoldenVerdict {
        val problems = mutableListOf<String>()
        val acted = run.correction?.to ?: run.outcome as? NluOutcome.Call
        val modelTool = (run.outcome as? NluOutcome.Call)?.name
        if (run.calledTool != case.expectedTool) problems += "called ${run.calledTool ?: "nothing"}, expected ${case.expectedTool ?: "nothing"}"
        if (acted != null && case.expectedTool != null) {
            for ((key, want) in case.expectedArgs) {
                val got = acted.args[key]
                when {
                    want == null && got != null -> problems += "$key=$got, expected absent"
                    want != null && (got == null || normalize(got) != normalize(want)) -> problems += "$key=${got ?: "missing"}, expected $want"
                }
            }
        }
        if (case.expectedTool != null && !case.remote && run.action?.ok != true) problems += "action failed: ${run.action?.message ?: run.failure ?: "none"}"
        return GoldenVerdict(problems.isEmpty(), modelTool == case.expectedTool && run.correction == null, problems)
    }

    /** `20%`, `20 %` and `20` are the same brightness; `Living Room` and `living_room` the same room. */
    fun normalize(value: String): String =
        value.trim().lowercase().removeSuffix("°c").removeSuffix("%").trim().replace(' ', '_').let { v ->
            v.toDoubleOrNull()?.let { d -> if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString() } ?: v
        }
}
