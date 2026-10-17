package app.couchcommander.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ArtScheduleTest {
    private val period = 10 * 60_000L

    @Test
    fun `every painting comes up once per round`() {
        val schedule = ArtSchedule(22)
        assertEquals((0 until 22).toSet(), (0L until 22L).map { schedule.indexAt(it) }.toSet())
    }

    @Test
    fun `the order is the same every time`() {
        assertEquals((0L until 22L).map { ArtSchedule(22).indexAt(it) }, (0L until 22L).map { ArtSchedule(22).indexAt(it) })
    }

    @Test
    fun `the painting changes at each period boundary and not in between`() {
        val schedule = ArtSchedule(22)
        val start = 1_800_000_000_000L - 1_800_000_000_000L % period
        assertEquals(schedule.slot(start, period), schedule.slot(start + period - 1, period))
        assertEquals(schedule.slot(start, period) + 1, schedule.slot(start + period, period))
        assertEquals(start + period, schedule.nextChange(start + 1234, period))
    }

    @Test
    fun `skipping moves to the next painting in the order`() {
        val schedule = ArtSchedule(22)
        assertEquals(schedule.indexAt(6), schedule.indexAt(5, skip = 1))
        assertNotEquals(schedule.indexAt(5), schedule.indexAt(5, skip = 1))
    }

    @Test
    fun `slots wrap around the catalogue`() {
        val schedule = ArtSchedule(3)
        assertEquals(schedule.indexAt(0), schedule.indexAt(3))
        assertEquals(schedule.indexAt(2), schedule.indexAt(-1))
    }
}
