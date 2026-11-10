package sk.ainet.examples.smarthome.distill

import sk.ainet.examples.smarthome.pipeline.GoldenSet
import sk.ainet.examples.smarthome.router.RouterFeatures
import sk.ainet.examples.smarthome.router.RouterLabels
import sk.ainet.examples.smarthome.tools.HomeTools
import kotlin.random.Random

/** One training phrase and the tool the generator meant (`null` = outside the home catalog). */
data class Phrase(val text: String, val intended: String?) {
    val label: Int get() = RouterLabels.indexOf(intended)
}

/**
 * Synthetic phrases for the router, generated from the typed catalog: enum values (rooms, doors, scenes, positions)
 * come from [HomeTools.catalog], templates add the ways people say them, and numbers are spelled out the way the
 * ASR writes them. The golden set is never generated: it stays held out as the test the talk reports.
 */
object Phrases {
    private fun enumOf(tool: String, arg: String): List<String> =
        HomeTools.catalog.functions.first { it.name == tool }.parameters.first { it.name == arg }.enum!!
            .map { it.replace('_', ' ') }

    private val rooms = enumOf(HomeTools.SET_LIGHT, HomeTools.Args.ROOM) + listOf("lounge", "office")
    private val doors = enumOf(HomeTools.SET_LOCK, HomeTools.Args.DOOR)
    private val scenes = enumOf(HomeTools.SET_SCENE, HomeTools.Args.SCENE)

    private val ones = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    fun spoken(n: Int): String = when {
        n < 20 -> ones[n]
        n == 100 -> "one hundred"
        n % 10 == 0 -> tens[n / 10]
        else -> "${tens[n / 10]} ${ones[n % 10]}"
    }

    private val templates: Map<String?, List<String>> = mapOf(
        HomeTools.SET_LIGHT to listOf(
            "turn on the {room} light", "turn off the {room} light", "switch on the light in the {room}",
            "switch the {room} lights off", "lights on in the {room}", "{room} lights off please",
            "dim the {room} light to {pct} percent", "set the {room} lamp to {pct} percent",
            "make the {room} brighter", "can you turn the {room} light on", "kill the lights in the {room}",
            "brighten the {room} to {pct} percent", "i need light in the {room}", "it's too dark in the {room}",
        ),
        HomeTools.SET_THERMOSTAT to listOf(
            "set the {room} to {deg} degrees", "make it {deg} degrees in the {room}", "heat the {room} to {deg}",
            "set the heating to {deg} degrees", "it's cold in the {room}", "make the {room} warmer",
            "turn the temperature down in the {room}", "i want {deg} degrees in the whole house",
            "set the thermostat to {deg}", "cool the {room} down to {deg} degrees", "warm up the house to {deg} degrees",
        ),
        HomeTools.SET_BLINDS to listOf(
            "close the {room} blinds", "open the blinds in the {room}", "lower the {room} shutters",
            "raise the blinds in the {room}", "open the {room} blinds halfway", "half close the {room} blinds",
            "pull down the blinds in the {room}", "let some light into the {room}", "shut the {room} blinds",
        ),
        HomeTools.SET_LOCK to listOf(
            "lock the {door}", "unlock the {door}", "is the {door} locked lock it", "please lock the {door} now",
            "open the lock on the {door}", "secure the {door}", "unlock the {door} for the delivery",
        ),
        HomeTools.SET_SCENE to listOf(
            "{scene} time", "activate the {scene} scene", "start {scene} mode", "set the house to {scene}",
            "switch to the {scene} scene", "good {scene}", "we are going {scene}", "run the {scene} routine",
        ),
        HomeTools.GET_STATUS to listOf(
            "what is the status of the {room}", "how is the {room}", "is the light on in the {room}",
            "what's going on in the {room}", "give me a status report", "what is on in the house",
            "check the {room}", "are the blinds closed in the {room}", "what is the temperature in the {room}",
        ),
        HomeTools.GET_WEATHER to listOf(
            "what is the weather like {when}", "will it rain {when}", "do i need an umbrella {when}",
            "what's the forecast for {when}", "how warm is it outside", "is it sunny in {city}",
            "weather in {city} {when}", "how cold will it be {when}", "is it going to snow {when}",
        ),
        null to listOf(
            "order a pizza", "play some jazz", "call my mother", "send a message to anna", "what time is it",
            "set an alarm for seven", "tell me a joke", "add milk to the shopping list", "who won the match yesterday",
            "book a table for two", "translate hello into french", "how far is the moon", "read me the news",
            "start a timer for ten minutes", "order a taxi to the airport", "what is the capital of peru",
            "remind me to call the dentist", "buy new headphones", "play the next episode", "how do i bake bread",
            "what's my bank balance", "open the garage on my phone app", "schedule a meeting tomorrow",
        ),
    )

    private val whens = listOf("today", "tomorrow", "now", "this evening", "tomorrow morning")
    private val cities = listOf("berlin", "vienna", "bratislava", "munich", "hamburg")
    private val prefixes = listOf("", "", "", "please ", "hey home ", "could you ", "okay ")

    /** Every template expanded with seeded random slot values, [perTemplate] times; golden utterances removed. */
    fun generate(perTemplate: Int = 12, seed: Long = 7L): List<Phrase> {
        val rnd = Random(seed)
        val held = GoldenSet.cases.map { RouterFeatures.normalize(it.utterance) }.toSet()
        val out = LinkedHashMap<String, Phrase>()
        for ((tool, list) in templates) for (t in list) repeat(perTemplate) {
            val text = prefixes.random(rnd) + t
                .replace("{room}", rooms.random(rnd)).replace("{door}", doors.random(rnd))
                .replace("{scene}", scenes.random(rnd)).replace("{pct}", spoken(listOf(10, 20, 25, 30, 40, 50, 60, 75, 80, 100).random(rnd)))
                .replace("{deg}", spoken(rnd.nextInt(16, 27))).replace("{when}", whens.random(rnd)).replace("{city}", cities.random(rnd))
            val key = RouterFeatures.normalize(text)
            if (key !in held) out.getOrPut(key) { Phrase(text, tool) }
        }
        return out.values.toList()
    }

    /** The held-out test: the golden set the talk and the on-device test report. */
    val heldOut: List<Phrase> get() = GoldenSet.cases.map { Phrase(it.utterance, it.expectedTool) }
}
