package sk.ainet.examples.smarthome.tools

import java.io.File

/** Writes [HomeTools.catalog] to the path given (the catalog the NLU cartridge is materialized with). */
fun main(args: Array<String>) {
    val out = File(args.singleOrNull() ?: error("usage: WriteToolCatalog <path/to/home-tools.v1.json>"))
    val json = HomeTools.catalog.toJson()
    val changed = !out.exists() || out.readText() != json
    if (changed) out.writeText(json)
    println("${if (changed) "wrote" else "unchanged"} ${out.path} (${HomeTools.catalog.id}, ${HomeTools.catalog.functions.size} functions)")
}
