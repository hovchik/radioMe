package com.radiome.core.dsp

import com.radiome.core.dsp.fec.BlockInterleaver
import com.radiome.core.dsp.fec.Crc16
import com.radiome.core.dsp.fec.Crc8
import com.radiome.core.dsp.fec.GF256
import com.radiome.core.dsp.fec.ReedSolomon
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ReedSolomonTest {
    @Test
    fun `field inverse and division are consistent`() {
        for (a in 1..255) {
            assertEquals(1, GF256.mul(a, GF256.inv(a)))
            for (b in listOf(1, 2, 3, 77, 255)) assertEquals(a, GF256.mul(GF256.div(a, b), b))
        }
    }

    @Test
    fun `crc check values`() {
        val check = "123456789".toByteArray()
        assertEquals(0x29B1, Crc16.compute(check))
        assertEquals(0xF4, Crc8.compute(check))
    }

    @Test
    fun `encoded codeword has zero syndromes`() {
        val rs = ReedSolomon(20)
        val cw = rs.encode(Random(1).nextBytes(59))
        assertTrue(rs.syndromes(cw).all { it == 0 })
    }

    @Test
    fun `corrects random errors and erasures up to capacity`() {
        val rnd = Random(42)
        for ((k, nsym) in listOf(59 to 30, 59 to 20, 59 to 12, 5 to 4, 200 to 54)) {
            val rs = ReedSolomon(nsym)
            repeat(300) {
                val data = rnd.nextBytes(k)
                val cw = rs.encode(data)
                val n = cw.size
                val erasureCount = rnd.nextInt(nsym + 1)
                val errorCount = rnd.nextInt((nsym - erasureCount) / 2 + 1)
                val positions = (0 until n).shuffled(java.util.Random(rnd.nextLong())).take(erasureCount + errorCount)
                val bad = cw.copyOf()
                for (p in positions) bad[p] = (bad[p].toInt() xor (1 + rnd.nextInt(255))).toByte()
                val res = rs.decode(bad, positions.take(erasureCount).toIntArray())
                assertTrue(res.success, "k=$k nsym=$nsym e=$errorCount s=$erasureCount")
                assertArrayEquals(data, res.data(nsym))
                assertEquals(erasureCount + errorCount, res.correctedBytes)
            }
        }
    }

    @Test
    fun `reports failure beyond capacity instead of silently corrupting most of the time`() {
        val rnd = Random(7)
        val rs = ReedSolomon(12)
        var failures = 0
        repeat(200) {
            val cw = rs.encode(rnd.nextBytes(59))
            val bad = cw.copyOf()
            (0 until cw.size).shuffled(java.util.Random(rnd.nextLong())).take(12).forEach { bad[it] = (bad[it].toInt() xor 0x5A).toByte() }
            if (!rs.decode(bad).success) failures++
        }
        assertTrue(failures > 190, "only $failures of 200 overloaded codewords were flagged")
    }

    @Test
    fun `parity ratio rounding`() {
        assertEquals(30, ReedSolomon.parityFor(59, 0.50))
        assertEquals(20, ReedSolomon.parityFor(59, 0.33))
        assertEquals(12, ReedSolomon.parityFor(59, 0.20))
    }

    @Test
    fun `interleaver round trip spreads bursts`() {
        val rnd = Random(3)
        val cws = List(10) { rnd.nextBytes(71) }
        val air = BlockInterleaver.interleave(cws, 4)
        assertEquals(710, air.size)
        val back = BlockInterleaver.deinterleave(air, 71, 10, 4)
        for (i in cws.indices) assertArrayEquals(cws[i], back[i])
        // A 16-byte burst inside the first block touches each of its 4 codewords exactly 4 times.
        val map = BlockInterleaver.airToCodewordIndex(71, 10, 4)
        val hits = (40 until 56).groupingBy { map[it] / 71 }.eachCount()
        assertEquals(mapOf(0 to 4, 1 to 4, 2 to 4, 3 to 4), hits)
        assertFalse(hits.containsKey(4))
    }
}
