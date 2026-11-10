package sk.ainet.examples.smarthome.tools

import sk.ainet.examples.smarthome.home.BlindPosition
import sk.ainet.examples.smarthome.home.Door
import sk.ainet.examples.smarthome.home.Room
import sk.ainet.examples.smarthome.home.Scene

/**
 * The home tool catalog, as code. This is the source of truth: `cartridges/catalogs/home-tools.v1.json`, which the NLU
 * cartridge is materialized with, is generated from [catalog] (`./gradlew :core:writeToolCatalog`) and a test pins
 * the checked-in file to it byte for byte. Enum values come from the home model, so a new room reaches the model's
 * prompt in the same commit that adds it to the home.
 */
object HomeTools {
    const val SET_LIGHT = "set_light"
    const val SET_THERMOSTAT = "set_thermostat"
    const val SET_BLINDS = "set_blinds"
    const val SET_LOCK = "set_lock"
    const val SET_SCENE = "set_scene"
    const val GET_STATUS = "get_status"
    const val GET_WEATHER = "get_weather"

    object Args {
        const val ROOM = "room"
        const val STATE = "state"
        const val BRIGHTNESS = "brightness"
        const val TEMPERATURE = "temperature"
        const val POSITION = "position"
        const val DOOR = "door"
        const val SCENE = "scene"
        const val WHEN = "when"
        const val PLACE = "place"
    }

    private val rooms = Room.entries.map { it.id }

    val catalog: ToolCatalog = toolCatalog("home-tools.v3") {
        function(SET_LIGHT, "Turn the light in a room on or off. Only for lights and lamps; never for temperature or heating.") {
            oneOf(Args.ROOM, "The room whose light to change.", rooms, required = true)
            oneOf(Args.STATE, "Whether the light should be on or off.", listOf("on", "off"), required = true)
            text(Args.BRIGHTNESS, "Light brightness in percent from 0 to 100, only when the user says a percentage or dim/bright. Never a temperature.")
        }
        function(SET_THERMOSTAT, "Set the heating temperature of a room or of the whole house. Use this whenever the user names degrees, a temperature, warmer or colder.") {
            oneOf(Args.ROOM, "The room to heat or cool. Omit for the whole home.", rooms)
            text(Args.TEMPERATURE, "Target temperature in degrees Celsius, for example 21. Any number of degrees the user says goes here.", required = true)
        }
        function(SET_BLINDS, "Open, close or half-open the window blinds in a room.") {
            oneOf(Args.ROOM, "The room whose blinds to move.", rooms, required = true)
            oneOf(Args.POSITION, "The blind position to move to.", BlindPosition.entries.map { it.id }, required = true)
        }
        function(SET_LOCK, "Lock or unlock a door of the house.") {
            oneOf(Args.DOOR, "Which door to lock or unlock.", Door.entries.map { it.id }, required = true)
            oneOf(Args.STATE, "Whether the door should be locked or unlocked.", listOf("locked", "unlocked"), required = true)
        }
        function(SET_SCENE, "Activate a scene that sets several devices at once: morning, movie, night or away.") {
            oneOf(Args.SCENE, "The scene to activate.", Scene.entries.map { it.id }, required = true)
        }
        function(GET_STATUS, "Report the current state of the devices in a room, or of the whole home.") {
            oneOf(Args.ROOM, "The room to report on. Omit for the whole home.", rooms)
        }
        // remote: the companion middleware executes it, the home model never sees it
        function(GET_WEATHER, "Report the weather or the forecast. Only for weather questions; never controls a device in the house.", remote = true) {
            oneOf(Args.WHEN, "Which weather to report. Omit for the current weather.", listOf("now", "today", "tomorrow"))
            text(Args.PLACE, "A city or place name, only when the user names one. Omit for the home location.")
        }
    }

    val names: Set<String> = catalog.names

    /** The functions that act on the home model itself; remote ones ([GET_WEATHER]) run on the companion. */
    val homeCommandNames: Set<String> = catalog.localNames

    /** Argument keys per function, as the catalog declares them. */
    val arguments: Map<String, Set<String>> = catalog.arguments
}
