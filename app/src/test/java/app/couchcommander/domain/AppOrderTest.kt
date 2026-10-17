package app.couchcommander.domain

import org.junit.Test
import kotlin.test.assertEquals

class AppOrderTest {
    private data class App(val key: String, val label: String)

    private val apps = listOf(App("tv", "Live TV"), App("yt", "YouTube"), App("ms", "Music"), App("ph", "Photos"))

    private fun shown(picked: List<String>) = AppOrder.shown(apps, picked, key = { it.key }).map { it.key }

    @Test
    fun `nothing is shown until apps are picked`() {
        assertEquals(emptyList(), shown(emptyList()))
    }

    @Test
    fun `picked apps show in picked order`() {
        assertEquals(listOf("yt", "ph", "tv"), shown(listOf("yt", "ph", "tv")))
    }

    @Test
    fun `uninstalled and repeated keys are skipped`() {
        assertEquals(listOf("ms", "tv"), shown(listOf("gone", "ms", "tv", "ms")))
    }

    @Test
    fun `toggle adds to the end and removes`() {
        assertEquals(listOf("a", "b", "c"), AppOrder.toggle(listOf("a", "b"), "c"))
        assertEquals(listOf("a", "c"), AppOrder.toggle(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `move shifts one place and stops at the ends`() {
        val row = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), AppOrder.move(row, "b", -1))
        assertEquals(listOf("a", "c", "b"), AppOrder.move(row, "b", 1))
        assertEquals(row, AppOrder.move(row, "a", -1))
        assertEquals(row, AppOrder.move(row, "c", 1))
        assertEquals(row, AppOrder.move(row, "missing", 1))
    }
}
