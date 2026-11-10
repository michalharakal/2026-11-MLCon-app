package sk.ainet.examples.smarthome.distill

import sk.ainet.examples.smarthome.tools.HomeTools

/**
 * A second held-out set, written by hand and deliberately unlike the generator's templates: other verbs, word
 * order, filler, indirect requests. The golden set measures the demo; this measures whether the router generalizes.
 * Never used for training or for choosing hyperparameters on.
 */
object Paraphrases {
    val cases: List<Phrase> = listOf(
        Phrase("could you get the lights going in the kitchen", HomeTools.SET_LIGHT),
        Phrase("bedroom lamp off thanks", HomeTools.SET_LIGHT),
        Phrase("i can barely see anything in the hallway", HomeTools.SET_LIGHT),
        Phrase("bring the living room lights down a bit", HomeTools.SET_LIGHT),
        Phrase("bathroom light at half brightness", HomeTools.SET_LIGHT),
        Phrase("lights out in the bedroom", HomeTools.SET_LIGHT),
        Phrase("i'm freezing in the bathroom", HomeTools.SET_THERMOSTAT),
        Phrase("crank the heat up in the living room", HomeTools.SET_THERMOSTAT),
        Phrase("twenty two degrees everywhere please", HomeTools.SET_THERMOSTAT),
        Phrase("it's way too hot in the kitchen", HomeTools.SET_THERMOSTAT),
        Phrase("put the thermostat on eighteen", HomeTools.SET_THERMOSTAT),
        Phrase("the sun is blinding me in the living room", HomeTools.SET_BLINDS),
        Phrase("roll the shades up in the kitchen", HomeTools.SET_BLINDS),
        Phrase("bedroom blinds down", HomeTools.SET_BLINDS),
        Phrase("open the shutters a little in the hallway", HomeTools.SET_BLINDS),
        Phrase("make sure nobody can get in through the back door", HomeTools.SET_LOCK),
        Phrase("let the guests in at the front door", HomeTools.SET_LOCK),
        Phrase("bolt the front door", HomeTools.SET_LOCK),
        Phrase("we're watching a film now", HomeTools.SET_SCENE),
        Phrase("time for bed", HomeTools.SET_SCENE),
        Phrase("we're heading out for the day", HomeTools.SET_SCENE),
        Phrase("rise and shine", HomeTools.SET_SCENE),
        Phrase("anything left on in the kitchen", HomeTools.GET_STATUS),
        Phrase("give me an overview of the house", HomeTools.GET_STATUS),
        Phrase("did i leave the bedroom light on", HomeTools.GET_STATUS),
        Phrase("how warm is the living room right now", HomeTools.GET_STATUS),
        Phrase("should i take a jacket tomorrow", HomeTools.GET_WEATHER),
        Phrase("what's it like outside", HomeTools.GET_WEATHER),
        Phrase("any storms coming tonight", HomeTools.GET_WEATHER),
        Phrase("temperature in hamburg tomorrow", HomeTools.GET_WEATHER),
        Phrase("play my workout playlist", null),
        Phrase("what's on my calendar", null),
        Phrase("order more coffee beans", null),
        Phrase("text john that i'm late", null),
        Phrase("how many calories in a banana", null),
        Phrase("turn on the tv", null),
        Phrase("open netflix", null),
        Phrase("water the plants", null),
    )
}
