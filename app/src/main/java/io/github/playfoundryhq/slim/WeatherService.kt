package io.github.playfoundryhq.slim

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Open-Meteo client (https://open-meteo.com).
 *
 * - Completely key-less and free for non-commercial use.
 * - Only used when the user explicitly enables "Real weather" in Settings
 *   (the launcher stays fully offline otherwise).
 */
class WeatherService(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {

    data class GeoResult(val name: String, val latitude: Float, val longitude: Float)

    data class CurrentWeather(
        val temperature: Double,
        val weatherCode: Int,
        val isDay: Boolean,
        // Quiet "next few hours" hint, e.g. "rain by 4pm" — null when nothing
        // notable changes in the lookahead window, so the chip stays exactly
        // as terse as before by default.
        val upcomingChange: String? = null
    ) {
        /** Maps WMO weather codes to a compact emoji + description. */
        fun emoji(): String = when (weatherCode) {
            0 -> if (isDay) "☀️" else "🌙"
            1, 2 -> if (isDay) "⛅" else "☁️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            in 51..57 -> "🌦️"
            in 61..67 -> "🌧️"
            in 71..77 -> "❄️"
            in 80..82 -> "🌧️"
            85, 86 -> "🌨️"
            95, 96, 99 -> "⛈️"
            else -> "🌡️"
        }

        fun description(): String = when (weatherCode) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            in 51..57 -> "Drizzle"
            in 61..67 -> "Rain"
            in 71..77 -> "Snow"
            in 80..82 -> "Showers"
            85, 86 -> "Snow showers"
            95, 96, 99 -> "Thunderstorm"
            else -> ""
        }
    }

    /** Resolves a city name to coordinates using the Open-Meteo geocoding API. */
    suspend fun geocodeCity(cityName: String): GeoResult? = withContext(ioDispatcher) {
        try {
            val encoded = URLEncoder.encode(cityName, "UTF-8")
            val json = httpGet(
                "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
            ) ?: return@withContext null
            val results = JSONObject(json).optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null
            val first = results.getJSONObject(0)
            GeoResult(
                name = first.getString("name"),
                latitude = first.getDouble("latitude").toFloat(),
                longitude = first.getDouble("longitude").toFloat()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetches the current weather for the given coordinates, plus a quiet
     * lookahead note (see [CurrentWeather.upcomingChange]) built from the same
     * response — Open-Meteo returns hourly data in the same call, so this costs
     * one extra query param and a few KB, not an extra request.
     */
    suspend fun fetchCurrentWeather(
        latitude: Float,
        longitude: Float,
        useFahrenheit: Boolean
    ): CurrentWeather? = withContext(ioDispatcher) {
        try {
            val unit = if (useFahrenheit) "fahrenheit" else "celsius"
            val json = httpGet(
                "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,weather_code,is_day,precipitation" +
                    "&hourly=weather_code,precipitation_probability" +
                    "&forecast_days=2&timezone=auto&temperature_unit=$unit"
            ) ?: return@withContext null
            val root = JSONObject(json)
            val current = root.getJSONObject("current")
            // The model's categorical weather_code can lag reality for brief,
            // localized showers (it's forecast, not a live sensor) — the
            // precipitation amount it reports alongside is a second, more
            // direct signal. If it says rain is actually falling right now but
            // the code hasn't caught up, trust the measurement over the label.
            val currentPrecipMm = current.optDouble("precipitation", 0.0)
            val rawCode = current.getInt("weather_code")
            val currentCode = if (currentPrecipMm > 0.0 && precipitationLabel(rawCode) == null) {
                WEATHER_CODE_LIGHT_RAIN
            } else {
                rawCode
            }
            CurrentWeather(
                temperature = current.getDouble("temperature_2m"),
                weatherCode = currentCode,
                isDay = current.optInt("is_day", 1) == 1,
                upcomingChange = runCatching {
                    findUpcomingChange(root.optJSONObject("hourly"), current.getString("time"), currentCode)
                }.getOrNull()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Scans the next [LOOKAHEAD_HOURS] hourly entries after [nowIso] for the
     * first hour where precipitation becomes likely (>= [PRECIP_THRESHOLD]%) or
     * the weather category changes from now (e.g. clear -> rain). Returns a
     * short note like "rain by 4pm", or null when nothing notable is coming —
     * the chip stays silent by default rather than narrating every little shift.
     */
    private fun findUpcomingChange(hourly: JSONObject?, nowIso: String, currentCode: Int): String? {
        if (hourly == null) return null
        val times = hourly.optJSONArray("time") ?: return null
        val codes = hourly.optJSONArray("weather_code") ?: return null
        val probs = hourly.optJSONArray("precipitation_probability")
        // `current.time` carries minutes ("...T13:45") but the hourly buckets are
        // on the hour ("...T13:00") — match on the hour prefix, not the full
        // string, or this never finds a start index and silently never fires.
        val nowHourPrefix = nowIso.take(13) // "YYYY-MM-DDTHH"
        val startIndex = (0 until times.length()).firstOrNull { times.getString(it).take(13) == nowHourPrefix }
            ?: return null
        val currentlyPrecip = precipitationLabel(currentCode) != null

        for (i in (startIndex + 1)..minOf(startIndex + LOOKAHEAD_HOURS, times.length() - 1)) {
            val hourCode = codes.getInt(i)
            val prob = probs?.optInt(i, 0) ?: 0
            val label = precipitationLabel(hourCode)
            val becomingWet = label != null && (!currentlyPrecip || prob >= PRECIP_THRESHOLD)
            if (becomingWet && label != null) {
                val hourLabel = isoHourToClockLabel(times.getString(i)) ?: continue
                return "$label by $hourLabel"
            }
        }
        return null
    }

    /** Maps a WMO code to a short precipitation word, or null if it's dry. */
    private fun precipitationLabel(code: Int): String? = when (code) {
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        95, 96, 99 -> "storms"
        else -> null
    }

    /** "2026-09-05T16:00" -> "4pm" (device locale/12h style, no minutes needed for an hour bucket). */
    private fun isoHourToClockLabel(iso: String): String? {
        val hour = iso.substringAfter('T', "").take(2).toIntOrNull() ?: return null
        val suffix = if (hour < 12) "am" else "pm"
        val hour12 = when (val h = hour % 12) { 0 -> 12; else -> h }
        return "$hour12$suffix"
    }

    private fun httpGet(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000

        /** Real weather is refreshed at most every 30 minutes. */
        const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L

        /** How far ahead the "upcoming change" hint looks. */
        private const val LOOKAHEAD_HOURS = 9

        /** Precipitation probability (%) that counts as "becoming wet" when the category isn't already precip. */
        private const val PRECIP_THRESHOLD = 50

        /** WMO "slight rain" — used as the fallback category when live precipitation contradicts the forecast code. */
        private const val WEATHER_CODE_LIGHT_RAIN = 61
    }
}
