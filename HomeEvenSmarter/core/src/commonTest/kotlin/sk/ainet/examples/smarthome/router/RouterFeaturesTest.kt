package sk.ainet.examples.smarthome.router

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The router is trained on the laptop and run on the phone: the features must be identical on every platform. */
class RouterFeaturesTest {
    @Test
    fun `normalize folds case, punctuation and spaces`() {
        assertEquals(" turn on the kitchen light ", RouterFeatures.normalize("Turn on the  Kitchen-light!"))
        assertEquals(" its cold ", RouterFeatures.normalize("it's cold"))
        assertEquals(" ", RouterFeatures.normalize(""))
    }

    @Test
    fun `buckets are pinned, so host and phone agree`() {
        val s = RouterFeatures.normalize("Turn on the kitchen light")
        assertEquals(PINNED, listOf(RouterFeatures.bucket(s, 0, 2), RouterFeatures.bucket(s, 1, 3), RouterFeatures.bucket(s, 5, 4)))
    }

    @Test
    fun `vector is unit length and order-sensitive only through n-grams`() {
        val v = RouterFeatures.vector("Turn on the kitchen light")
        assertEquals(RouterFeatures.DIM, v.size)
        assertTrue(abs(sqrt(v.sumOf { (it * it).toDouble() }) - 1.0) < 1e-5)
        assertTrue(RouterFeatures.vector("").all { it == 0f })
    }

    private companion object { val PINNED = listOf(83, 435, 42) }
}
