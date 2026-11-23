package com.vector.console.data.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue feeds are hand-formatted text and every one of them breaks
 * differently. These cases are the shapes actually observed from CelesTrak plus
 * the two ways a file can be damaged on disk.
 */
class TleParseTest {

    private val issName = "ISS (ZARYA)"
    private val issL1 = "1 25544U 98067A   08264.51782528 -.00002182  00000-0 -11606-4 0  2927"
    private val issL2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537"

    @Test
    fun `named three line record keeps the name`() {
        val records = TleStore.parseTle("$issName\n$issL1\n$issL2\n")
        assertEquals(1, records.size)
        assertEquals(issName, records[0].name)
        assertEquals(issL1, records[0].line1)
        assertEquals(issL2, records[0].line2)
    }

    @Test
    fun `bare two line record parses with an empty name`() {
        val records = TleStore.parseTle("$issL1\n$issL2")
        assertEquals(1, records.size)
        assertEquals("", records[0].name)
        assertEquals(issL1, records[0].line1)
    }

    @Test
    fun `carriage returns and blank lines do not shift records`() {
        val body = "$issName\r\n$issL1\r\n$issL2\r\n\r\n" +
            "GPS BIIR-2  (PRN 13)\r\n" +
            "1 24876U 97035A   26200.50000000  .00000000  00000-0  00000-0 0  9993\r\n" +
            "2 24876  55.4000 120.0000 0050000  60.0000 300.0000  2.00563000 10000\r\n"
        val records = TleStore.parseTle(body)
        assertEquals(2, records.size)
        assertEquals(issName, records[0].name)
        assertEquals("GPS BIIR-2  (PRN 13)", records[1].name)
        assertTrue(records[1].line1.startsWith("1 24876"))
    }

    @Test
    fun `a stray header line is skipped without consuming the pair`() {
        val body = "GP data for the following 8 satellites\n$issL1\n$issL2\n"
        val records = TleStore.parseTle(body)
        assertEquals(1, records.size)
        assertEquals(issL1, records[0].line1)
    }

    @Test
    fun `a truncated final record is dropped, not half parsed`() {
        val body = "$issL1\n$issL2\n$issName\n$issL1\n"
        val records = TleStore.parseTle(body)
        assertEquals(1, records.size)
    }

    @Test
    fun `empty and whitespace input yields nothing`() {
        assertTrue(TleStore.parseTle("").isEmpty())
        assertTrue(TleStore.parseTle("   \n\n \t\n").isEmpty())
    }

    @Test
    fun `trailing padding is trimmed but columns are preserved`() {
        val padded = "$issL1   \n$issL2   \n"
        val records = TleStore.parseTle(padded)
        assertEquals(1, records.size)
        assertEquals(69, records[0].line1.length)
        assertEquals(69, records[0].line2.length)
        // The parsed record must still initialise: padding damage shows up here.
        val rec = Sgp4.twoline2satrec(records[0].line1, records[0].line2)
        assertTrue(rec != null)
    }
}
