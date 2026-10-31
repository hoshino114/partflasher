package com.partflasher.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun formatsByteSizes() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.00 KiB", Format.bytes(1024))
        assertEquals("1.50 KiB", Format.bytes(1536))
        assertEquals("1.00 MiB", Format.bytes(1024L * 1024L))
        assertEquals("1.00 GiB", Format.bytes(1024L * 1024L * 1024L))
        assertEquals("未知", Format.bytes(-1))
    }

    @Test
    fun formatsDurations() {
        assertEquals("0 秒", Format.duration(0))
        assertEquals("5 秒", Format.duration(5_500))
        assertEquals("1 分 5 秒", Format.duration(65_000))
        assertEquals("1 小时 2 分 3 秒", Format.duration(3_723_000))
        assertEquals("未知", Format.duration(-1))
    }

    @Test
    fun formatsSpeeds() {
        assertEquals("0 B/s", Format.speed(0))
        assertEquals("1.00 MiB/s", Format.speed(1024L * 1024L))
        assertTrue(Format.speed(2048).endsWith("/s"))
    }
}
