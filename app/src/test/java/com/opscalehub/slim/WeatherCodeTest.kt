package com.opscalehub.slim

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic coverage for the WMO weather-code mapping used by the header
 * weather chip. No Android dependencies, so it runs as a plain JVM unit test.
 */
class WeatherCodeTest {

    private fun weather(code: Int, isDay: Boolean = true) =
        WeatherService.CurrentWeather(temperature = 0.0, weatherCode = code, isDay = isDay)

    @Test
    fun clearSky_dayAndNight() {
        assertEquals("☀️", weather(0, isDay = true).emoji())
        assertEquals("🌙", weather(0, isDay = false).emoji())
        assertEquals("Clear", weather(0).description())
    }

    @Test
    fun rainRange_mapsToRain() {
        for (code in 61..67) {
            assertEquals("🌧️", weather(code).emoji())
            assertEquals("Rain", weather(code).description())
        }
    }

    @Test
    fun snowRange_mapsToSnow() {
        assertEquals("❄️", weather(71).emoji())
        assertEquals("Snow", weather(75).description())
    }

    @Test
    fun thunderstorm_mapsToStorm() {
        assertEquals("⛈️", weather(95).emoji())
        assertEquals("Thunderstorm", weather(99).description())
    }

    @Test
    fun unknownCode_fallsBackToThermometer() {
        assertEquals("🌡️", weather(12345).emoji())
        assertEquals("", weather(12345).description())
    }
}
