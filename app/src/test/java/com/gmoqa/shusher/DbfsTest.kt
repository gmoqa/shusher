package com.gmoqa.shusher

import org.junit.Assert.assertEquals
import org.junit.Test

class DbfsTest {
    @Test
    fun levels() {
        assertEquals(-90.0, ShushService.dbfs(ShortArray(100), 100), 0.0)
        assertEquals(0.0, ShushService.dbfs(ShortArray(100) { Short.MIN_VALUE }, 100), 0.01)
        // media escala ≈ -6 dB
        assertEquals(-6.02, ShushService.dbfs(ShortArray(100) { 16384 }, 100), 0.01)
    }
}
