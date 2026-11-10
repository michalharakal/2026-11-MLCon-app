package sk.ainet.examples.smarthome.router

import sk.ainet.examples.smarthome.tools.HomeTools

/**
 * The router's output classes, in logit order: every catalog function, then [NONE] for requests outside the home
 * catalog. Derived from [HomeTools.catalog], so the router and the NLU always share one vocabulary of tools.
 */
object RouterLabels {
    const val NONE = "none"
    val all: List<String> = HomeTools.catalog.functions.map { it.name } + NONE
    fun indexOf(label: String?): Int = all.indexOf(label ?: NONE).also { require(it >= 0) { "unknown router label $label" } }
}
