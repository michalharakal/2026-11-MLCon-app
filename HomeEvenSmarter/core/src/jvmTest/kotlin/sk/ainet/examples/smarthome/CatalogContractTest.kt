package sk.ainet.examples.smarthome

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import sk.ainet.examples.smarthome.tools.HomeTools
import sk.ainet.examples.smarthome.tools.IntentMapper
import sk.ainet.examples.smarthome.tools.Mapping
import sk.ainet.examples.smarthome.tools.toolCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The catalog is code ([HomeTools.catalog]); the JSON the NLU cartridge is materialized with is its rendering.
 * Descriptions are model input, so the checked-in file must be exactly what the code renders — byte for byte.
 */
class CatalogContractTest {
    private val file = File("../cartridges/catalogs/home-tools.v1.json")
    private val catalog = Json.parseToJsonElement(file.readText()).jsonObject
    private val functions = catalog.getValue("functions").jsonArray.map { it.jsonObject }

    @Test
    fun `checked-in catalog is the rendering of HomeTools catalog`() {
        assertEquals(file.readText(), HomeTools.catalog.toJson(), "stale catalog: run ./gradlew :core:writeToolCatalog")
    }

    @Test
    fun `names stay few and only the weather is remote`() {
        assertEquals(HomeTools.names, functions.map { it.getValue("name").jsonPrimitive.content }.toSet())
        assertTrue(functions.size <= 7, "a 270M model degrades beyond a handful of functions")
        assertEquals(setOf(HomeTools.GET_WEATHER), HomeTools.names - HomeTools.homeCommandNames)
    }

    @Test
    fun `every enum value maps to a home command`() {
        for (f in HomeTools.catalog.functions.filterNot { it.remote }) {
            val sample = f.parameters.associate { p -> p.name to (p.enum?.first() ?: if (p.name == HomeTools.Args.TEMPERATURE) "21" else "50") }
            val minimal = sample.filterKeys { k -> k in f.required || k != HomeTools.Args.BRIGHTNESS }
            assertIs<Mapping.Command>(IntentMapper.map(f.name, minimal), "${f.name} with $minimal")
            for (p in f.parameters) p.enum?.forEach { e ->
                assertIs<Mapping.Command>(IntentMapper.map(f.name, sample + (p.name to e)), "${f.name} ${p.name}=$e")
            }
        }
    }

    @Test
    fun `the DSL rejects duplicates`() {
        assertFailsWith<IllegalArgumentException> { toolCatalog("x") { function("a", "A"); function("a", "B") } }
        assertFailsWith<IllegalArgumentException> { toolCatalog("x") { function("a", "A") { text("p", "P"); text("p", "Q") } } }
    }
}
