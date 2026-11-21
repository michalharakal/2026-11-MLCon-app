package sk.ainet.examples.moonshine

import org.jetbrains.compose.resources.ExperimentalResourceApi
import sk.ainet.examples.moonshine.resources.Res

/** What the bundled sample says, for comparing against the transcript. */
const val SAMPLE_TEXT: String = "For good or for evil, in the superlative degree of comparison only."

/**
 * The bundled utterance: five seconds from the first paragraph of *A Tale of Two Cities*, a public-domain LibriVox
 * reading (the Moonshine repository's `test-assets/two_cities_16k.wav`, 36.8 s to 41.8 s). One resource, read the
 * same way on every platform.
 */
@OptIn(ExperimentalResourceApi::class)
suspend fun sampleUtterance(): FloatArray = decodeWav(Res.readBytes("files/sample.wav"))
