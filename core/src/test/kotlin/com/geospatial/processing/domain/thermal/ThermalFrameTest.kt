package com.geospatial.processing.domain.thermal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ThermalFrameTest {

    private val params = ThermalParams(
        emissivity = 0.95, reflectedTempC = 20.0, atmosphericTempC = 20.0, distanceM = 5.0, humidityPct = 50.0,
        irWindowTempC = 20.0, irWindowTransmission = 1.0, planckR1 = 1.0, planckR2 = 1.0, planckB = 1.0, planckF = 1.0,
        planckO = 0.0, atmAlpha1 = 0.0, atmAlpha2 = 0.0, atmBeta1 = 0.0, atmBeta2 = 0.0, atmX = 1.0,
    )

    // 3×2:  10  20  30
    //       40  NaN 60
    private val frame = ThermalFrame(3, 2, floatArrayOf(10f, 20f, 30f, 40f, Float.NaN, 60f), params)

    @Test
    fun `temperature lookup is bounds-checked and skips unreadable pixels`() {
        assertEquals(30f, frame.temperatureAt(2, 0))
        assertNull(frame.temperatureAt(1, 1))   // NaN
        assertNull(frame.temperatureAt(3, 0))
        assertNull(frame.temperatureAt(-1, 0))
        assertNull(frame.temperatureAt(0, 2))
    }

    @Test
    fun `whole-frame stats ignore NaN and locate the hottest pixel`() {
        val s = frame.stats()!!

        assertEquals(10f, s.min)
        assertEquals(60f, s.max)
        assertEquals(32f, s.mean)
        assertEquals(5, s.pixelCount)
        assertEquals(Pixel(0, 0), s.minAt)
        assertEquals(Pixel(2, 1), s.maxAt)
    }

    @Test
    fun `region stats cover only the region and are clipped to the frame`() {
        val right = frame.stats(Region(2, 0, 2, 1))!!
        assertEquals(30f, right.min)
        assertEquals(60f, right.max)
        assertEquals(2, right.pixelCount)

        val oversized = frame.stats(Region(-5, -5, 99, 99))!!
        assertEquals(5, oversized.pixelCount)

        val flipped = frame.stats(Region(2, 1, 0, 0))!! // corners given in any order
        assertEquals(5, flipped.pixelCount)
    }

    @Test
    fun `a region with no valid pixel has no stats`() {
        assertNull(frame.stats(Region(1, 1, 1, 1)))
    }

    @Test
    fun `frame size must match its data`() {
        assertFailsWith<IllegalArgumentException> { ThermalFrame(2, 2, FloatArray(3), params) }
    }

    @Test
    fun `palette runs from cold to hot, clamps, and makes unreadable pixels transparent`() {
        val p = ThermalPalette.GRAYSCALE
        assertEquals(0xFF000000.toInt(), p.argb(0f))
        assertEquals(0xFFFFFFFF.toInt(), p.argb(1f))
        assertEquals(p.argb(0f), p.argb(-3f))
        assertEquals(p.argb(1f), p.argb(7f))
        assertNotEquals(ThermalPalette.IRON.argb(0.2f), ThermalPalette.IRON.argb(0.8f))

        val pixels = p.render(frame)
        assertEquals(0xFF000000.toInt(), pixels[0])   // coldest
        assertEquals(0xFFFFFFFF.toInt(), pixels[5])   // hottest
        assertEquals(0, pixels[4])                    // NaN
    }

    @Test
    fun `overrides replace only the fields they set`() {
        val changed = params.with(ThermalOverrides(emissivity = 0.5))

        assertEquals(0.5, changed.emissivity)
        assertEquals(params.distanceM, changed.distanceM)
    }
}
