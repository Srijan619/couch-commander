package app.couchcommander.domain

import java.util.Random

/**
 * Which painting is up at a given time. The order is a fixed shuffle of the catalogue and the position follows the
 * clock, so home and the screensaver always agree and nothing has to be stored.
 */
class ArtSchedule(size: Int, seed: Long = 1866L) {
    init {
        require(size > 0) { "empty catalogue" }
    }

    private val order: IntArray = IntArray(size) { it }.also { a ->
        val random = Random(seed)
        for (i in a.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
    }

    /** The slot number for [epochMillis]: it goes up by one every [periodMillis]. */
    fun slot(epochMillis: Long, periodMillis: Long): Long = Math.floorDiv(epochMillis, periodMillis)

    /** When the slot after [epochMillis] starts. */
    fun nextChange(epochMillis: Long, periodMillis: Long): Long = (slot(epochMillis, periodMillis) + 1) * periodMillis

    /** The catalogue index shown in [slot]. [skip] counts "Next artwork" presses. */
    fun indexAt(slot: Long, skip: Long = 0): Int = order[Math.floorMod(slot + skip, order.size.toLong()).toInt()]
}
