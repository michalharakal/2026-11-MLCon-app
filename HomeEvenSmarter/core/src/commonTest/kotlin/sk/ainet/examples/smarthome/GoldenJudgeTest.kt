package sk.ainet.examples.smarthome

import sk.ainet.examples.smarthome.actions.ActionResult
import sk.ainet.examples.smarthome.engine.NluOutcome
import sk.ainet.examples.smarthome.pipeline.GoldenCase
import sk.ainet.examples.smarthome.pipeline.GoldenSet
import sk.ainet.examples.smarthome.pipeline.PipelineRun
import sk.ainet.examples.smarthome.tools.HomeTools
import sk.ainet.examples.smarthome.tools.HostRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldenJudgeTest {
    private val timing = NluOutcome.Timing(0, 0, 0, 0, 0)
    private fun call(name: String, vararg args: Pair<String, String>) = NluOutcome.Call(name, mapOf(*args), "", timing)
    private fun case(utterance: String) = GoldenSet.cases.first { it.utterance == utterance }

    private fun run(case: GoldenCase, outcome: NluOutcome?, ok: Boolean = true): PipelineRun {
        val correction = (outcome as? NluOutcome.Call)?.let { HostRules.apply(case.utterance, it) }
        val tool = correction?.to?.name ?: (outcome as? NluOutcome.Call)?.name
        return PipelineRun(case.utterance, outcome, correction, tool?.let { ActionResult(it, ok, "") }, null, 0, 0, 0)
    }

    @Test
    fun `arguments are checked, not only the function`() {
        val c = case("Turn on the kitchen light")
        assertTrue(GoldenSet.judge(c, run(c, call(HomeTools.SET_LIGHT, "room" to "kitchen", "state" to "on", "brightness" to "100%"))).passed)
        val wrongRoom = GoldenSet.judge(c, run(c, call(HomeTools.SET_LIGHT, "room" to "bedroom", "state" to "on")))
        assertFalse(wrongRoom.passed)
        assertEquals(listOf("room=bedroom, expected kitchen"), wrongRoom.problems)
    }

    @Test
    fun `values are compared after normalizing units and spelling`() {
        val c = case("Dim the living room light to twenty percent")
        assertTrue(GoldenSet.judge(c, run(c, call(HomeTools.SET_LIGHT, "room" to "Living Room", "brightness" to "20%", "state" to "on"))).passed)
        assertEquals("21", GoldenSet.normalize("21.0 °C"))
    }

    @Test
    fun `a host-rule rescue passes the pipeline but not the model alone`() {
        val c = case("Set the bedroom to twenty one degrees")
        val v = GoldenSet.judge(c, run(c, call(HomeTools.SET_LIGHT, "room" to "bedroom", "brightness" to "21", "state" to "on")))
        assertTrue(v.passed, "${v.problems}")
        assertFalse(v.modelExact)
    }

    @Test
    fun `the whole house must not carry a room`() {
        val c = case("Make it nineteen degrees in the whole house")
        assertTrue(GoldenSet.judge(c, run(c, call(HomeTools.SET_LIGHT, "room" to "bedroom", "brightness" to "19"))).passed, "host rule drops the room")
        val direct = GoldenSet.judge(c, run(c, call(HomeTools.SET_THERMOSTAT, "room" to "bedroom", "temperature" to "19")))
        assertEquals(listOf("room=bedroom, expected absent"), direct.problems)
    }

    @Test
    fun `out of catalog must not call, remote tools do not need the companion`() {
        val pizza = case("Order a pizza for dinner")
        assertTrue(GoldenSet.judge(pizza, run(pizza, NluOutcome.NoCall("no", timing))).passed)
        val weather = case("What is the weather like tomorrow")
        assertTrue(GoldenSet.judge(weather, run(weather, call(HomeTools.GET_WEATHER, "when" to "tomorrow"), ok = false)).passed)
        val lock = case("Lock the front door")
        assertFalse(GoldenSet.judge(lock, run(lock, call(HomeTools.SET_LOCK, "door" to "front_door", "state" to "locked"), ok = false)).passed)
    }
}
