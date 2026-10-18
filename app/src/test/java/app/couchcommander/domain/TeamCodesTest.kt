package app.couchcommander.domain

import org.junit.Test
import kotlin.test.assertEquals

class TeamCodesTest {
    @Test
    fun `known clubs, then made from the name`() {
        assertEquals("MCI", TeamCodes.code("Manchester City"))
        assertEquals("BVB", TeamCodes.code("Borussia Dortmund"))
        assertEquals("FCB", TeamCodes.code("Bayern München"))
        assertEquals("GIR", TeamCodes.code("Girona FC"))
        assertEquals("SCP", TeamCodes.code("Sporting CP"))
        assertEquals("KUP", TeamCodes.code("KuPS"))
        assertEquals("VPA", TeamCodes.code("Vaasan Palloseura"))
    }
}
