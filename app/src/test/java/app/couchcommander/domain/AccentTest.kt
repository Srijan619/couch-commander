package app.couchcommander.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccentTest {
    private fun hsv(argb: Int) = FloatArray(3).also { Accent.toHsv((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, it) }

    @Test
    fun `greys and blacks give the fallback`() {
        val pixels = IntArray(100) { if (it % 2 == 0) 0xFF808080.toInt() else 0xFF050505.toInt() }
        assertEquals(Accent.FALLBACK, Accent.vibrant(pixels))
    }

    @Test
    fun `a small vivid area beats a large grey one`() {
        val pixels = IntArray(1000) { if (it < 80) 0xFF1E50D0.toInt() else 0xFF8A8A84.toInt() }
        val h = hsv(Accent.vibrant(pixels))
        assertTrue(h[0] in 215f..235f, "hue ${h[0]} should be blue")
    }

    @Test
    fun `the strongest colour family wins and comes out bright`() {
        val pixels = IntArray(1000) { if (it < 700) 0xFFE0B020.toInt() else 0xFF2050A0.toInt() }
        val h = hsv(Accent.vibrant(pixels))
        assertTrue(h[0] in 40f..50f, "hue ${h[0]} should be gold")
        assertTrue(h[1] >= 0.7f && h[2] >= 0.9f)
    }

    @Test
    fun `hsv round trip`() {
        for (c in listOf(0xFFFF5A36.toInt(), 0xFF6C4DFF.toInt(), 0xFFC8F031.toInt(), 0xFF3FD3F5.toInt())) {
            val h = hsv(c)
            val back = Accent.fromHsv(h[0], h[1], h[2])
            for (shift in listOf(16, 8, 0)) {
                assertTrue(kotlin.math.abs(((c shr shift) and 0xFF) - ((back shr shift) and 0xFF)) <= 1, "channel off for ${c.toString(16)}")
            }
        }
    }

    @Test
    fun `wall is a dark version of the accent`() {
        val h = hsv(Accent.wall(0xFFFF5A36.toInt()))
        assertEquals(0.3f, h[2], 0.01f)
    }
}
