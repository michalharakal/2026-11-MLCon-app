package sk.ainet.examples.smarthome.tools

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A function-calling tool catalog as code. The descriptions are model input: FunctionGemma reads them in its prompt,
 * so wording changes are model changes and go through review like any other code. [toJson] renders the file the NLU
 * cartridge is materialized with (`cartridges/catalogs/home-tools.v1.json`); a test pins it byte for byte.
 */
data class ToolCatalog(val id: String, val languages: List<String>, val functions: List<Function>) {

    data class Function(
        val name: String,
        val description: String,
        val parameters: List<Parameter>,
        /** Executed by the companion middleware, not by the on-device home model. Not part of the model input. */
        val remote: Boolean,
    ) {
        val required: List<String> get() = parameters.filter { it.required }.map { it.name }
    }

    data class Parameter(val name: String, val description: String, val enum: List<String>?, val required: Boolean)

    val names: Set<String> get() = functions.mapTo(linkedSetOf()) { it.name }
    val localNames: Set<String> get() = functions.filterNot { it.remote }.mapTo(linkedSetOf()) { it.name }
    val arguments: Map<String, Set<String>> get() = functions.associate { f -> f.name to f.parameters.mapTo(linkedSetOf()) { it.name } }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("languages", JsonArray(languages.map(::JsonPrimitive)))
        put("functions", JsonArray(functions.map { f ->
            buildJsonObject {
                put("name", f.name)
                put("description", f.description)
                put("parameters", buildJsonObject {
                    for (p in f.parameters) put(p.name, buildJsonObject {
                        put("type", "string")
                        put("description", p.description)
                        p.enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
                    })
                })
                if (f.required.isNotEmpty()) put("required", JsonArray(f.required.map(::JsonPrimitive)))
            }
        }))
    }

    /** Two-space pretty JSON with a trailing newline, the layout of the checked-in catalog. */
    fun toJson(): String = pretty.encodeToString(JsonObject.serializer(), toJsonObject()) + "\n"

    private companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    }
}

@DslMarker
annotation class ToolCatalogDsl

/** `toolCatalog("home-tools.v3") { function("set_light", "…") { oneOf("room", "…", rooms, required = true) } }` */
fun toolCatalog(id: String, languages: List<String> = listOf("en"), block: ToolCatalogBuilder.() -> Unit): ToolCatalog =
    ToolCatalogBuilder().apply(block).let { b ->
        val catalog = ToolCatalog(id, languages, b.functions.toList())
        val duplicate = catalog.functions.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicate.isEmpty()) { "duplicate functions $duplicate" }
        catalog
    }

@ToolCatalogDsl
class ToolCatalogBuilder internal constructor() {
    internal val functions = mutableListOf<ToolCatalog.Function>()

    fun function(name: String, description: String, remote: Boolean = false, block: FunctionBuilder.() -> Unit = {}) {
        val params = FunctionBuilder().apply(block).parameters.toList()
        require(params.map { it.name }.toSet().size == params.size) { "$name: duplicate parameter" }
        functions += ToolCatalog.Function(name, description, params, remote)
    }
}

@ToolCatalogDsl
class FunctionBuilder internal constructor() {
    internal val parameters = mutableListOf<ToolCatalog.Parameter>()

    /** A free-text argument (numbers stay strings: the model writes what the user said, the mapper parses it). */
    fun text(name: String, description: String, required: Boolean = false) {
        parameters += ToolCatalog.Parameter(name, description, null, required)
    }

    /** An argument restricted to [values], typically the wire ids of a home-model enum. */
    fun oneOf(name: String, description: String, values: List<String>, required: Boolean = false) {
        require(values.isNotEmpty()) { "$name: empty enum" }
        parameters += ToolCatalog.Parameter(name, description, values, required)
    }
}
