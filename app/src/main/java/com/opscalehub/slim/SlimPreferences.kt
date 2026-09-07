package com.opscalehub.slim

import android.content.Context
import android.content.SharedPreferences

/**
 * Central wrapper around SharedPreferences holding every user-configurable
 * launcher option. All feature toggles introduced by the Settings screen
 * read and write through this class.
 */
class SlimPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Home screen ----
    var showClock: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CLOCK, value).apply()

    var showDate: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DATE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DATE, value).apply()

    var use24HourFormat: Boolean
        get() = prefs.getBoolean(KEY_24_HOUR, true)
        set(value) = prefs.edit().putBoolean(KEY_24_HOUR, value).apply()

    // ---- World clock ----
    // A single optional secondary clock, e.g. for keeping a remote timezone in
    // view. Empty timezone ID means "off" — there is no separate boolean so the
    // two settings can't drift out of sync.
    /** IANA timezone ID (e.g. "America/New_York"), or "" when the world clock is off. */
    var worldClockTimeZoneId: String
        get() = prefs.getString(KEY_WORLD_CLOCK_TZ, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WORLD_CLOCK_TZ, value).apply()

    /** Short display label for the world clock (city name), e.g. "New York". */
    var worldClockLabel: String
        get() = prefs.getString(KEY_WORLD_CLOCK_LABEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WORLD_CLOCK_LABEL, value).apply()

    // ---- Weather ----
    /** One of [WEATHER_OFF], [WEATHER_SIMULATED], [WEATHER_REAL]. */
    var weatherMode: String
        get() = prefs.getString(KEY_WEATHER_MODE, WEATHER_SIMULATED) ?: WEATHER_SIMULATED
        set(value) = prefs.edit().putString(KEY_WEATHER_MODE, value).apply()

    var weatherCity: String
        get() = prefs.getString(KEY_WEATHER_CITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEATHER_CITY, value).apply()

    var weatherLatitude: Float
        get() = prefs.getFloat(KEY_WEATHER_LAT, Float.NaN)
        set(value) = prefs.edit().putFloat(KEY_WEATHER_LAT, value).apply()

    var weatherLongitude: Float
        get() = prefs.getFloat(KEY_WEATHER_LON, Float.NaN)
        set(value) = prefs.edit().putFloat(KEY_WEATHER_LON, value).apply()

    var useFahrenheit: Boolean
        get() = prefs.getBoolean(KEY_WEATHER_FAHRENHEIT, false)
        set(value) = prefs.edit().putBoolean(KEY_WEATHER_FAHRENHEIT, value).apply()

    /** Cached last successful weather text so the header never flashes empty. */
    var lastWeatherText: String
        get() = prefs.getString(KEY_WEATHER_CACHE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEATHER_CACHE, value).apply()

    var lastWeatherFetchTime: Long
        get() = prefs.getLong(KEY_WEATHER_CACHE_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_WEATHER_CACHE_TIME, value).apply()

    // ---- Search ----
    var searchHistoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_HISTORY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SEARCH_HISTORY_ENABLED, value).apply()

    /**
     * Recent apps launched from search results, most recent first.
     * Stored as package names separated by [HISTORY_SEPARATOR].
     */
    fun getSearchHistory(): List<String> {
        val raw = prefs.getString(KEY_SEARCH_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(HISTORY_SEPARATOR).filter { it.isNotEmpty() }
    }

    fun addToSearchHistory(packageName: String) {
        if (!searchHistoryEnabled) return
        val history = getSearchHistory().toMutableList()
        history.remove(packageName)
        history.add(0, packageName)
        val trimmed = history.take(MAX_HISTORY_SIZE)
        prefs.edit().putString(KEY_SEARCH_HISTORY, trimmed.joinToString(HISTORY_SEPARATOR)).apply()
    }

    fun clearSearchHistory() {
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    // ---- Appearance ----
    /** Text-only mode: hide app icons for an ultra-minimal list. */
    var showAppIcons: Boolean
        get() = prefs.getBoolean(KEY_SHOW_APP_ICONS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_APP_ICONS, value).apply()

    // ---- Background ----
    /** Background mode: [BG_TRANSPARENT], [BG_DIMMED], or [BG_SOLID_BLACK]. */
    var backgroundMode: String
        get() = prefs.getString(KEY_BACKGROUND_MODE, BG_SOLID_BLACK) ?: BG_SOLID_BLACK
        set(value) = prefs.edit().putString(KEY_BACKGROUND_MODE, value).apply()

    /** Immersive mode: hide system status bar, show notification count + battery in header. */
    var immersiveMode: Boolean
        get() = prefs.getBoolean(KEY_IMMERSIVE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_IMMERSIVE_MODE, value).apply()

    /**
     * Focus screen: long-pressing the home-screen clock drops a black
     * clock-only veil over everything, dismissed by a tap or a swipe up. Off by
     * default (so the long-press does nothing until enabled). It is a
     * self-imposed "blank the phone down to a clock" toggle drawn inside Slim's
     * own window — NOT a lock screen: it does not run over the keyguard, shows
     * no protected content, and does not touch Android's secure lock. It is a
     * deliberate gesture, never tied to screen-off / unlock (a launcher cannot
     * reliably paint anything at wake time, nor over the secure keyguard).
     */
    var focusScreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOCUS_SCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_FOCUS_SCREEN, value).apply()

    // ---- Widget ----
    /**
     * The single app-widget id currently bound to Slim's home screen, or
     * [NO_WIDGET] when none is set. Allocated by [WidgetHostManager]'s host and
     * persisted here so the widget survives restarts and is re-rendered on launch.
     */
    var widgetId: Int
        get() = prefs.getInt(KEY_WIDGET_ID, NO_WIDGET)
        set(value) = prefs.edit().putInt(KEY_WIDGET_ID, value).apply()

    // ---- Gestures ----
    var swipeUpForSearch: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_UP_SEARCH, true)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_UP_SEARCH, value).apply()

    /** Swipe down anywhere on the home screen to open the notification shade. */
    var swipeDownForNotifications: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_DOWN_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_DOWN_NOTIFICATIONS, value).apply()

    // ---- Notifications ----
    /**
     * When true (default), only communication notifications (message/call/email/
     * social) surface on the home screen; everything else is filtered out as
     * noise. Ongoing / foreground-service notifications are always dropped, and
     * media gets its own row, regardless of this toggle.
     */
    var communicationNotificationsOnly: Boolean
        get() = prefs.getBoolean(KEY_COMM_NOTIFICATIONS_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_COMM_NOTIFICATIONS_ONLY, value).apply()

    // ---- Backup / Restore ----

    /** Serializes every user preference to JSON (for settings backup). */
    fun exportToJson(): org.json.JSONObject {
        val json = org.json.JSONObject()
        for ((key, value) in prefs.all) {
            json.put(key, value)
        }
        return json
    }

    /** Restores preferences from a backup created by [exportToJson]. */
    fun importFromJson(json: org.json.JSONObject) {
        val editor = prefs.edit()
        for (key in json.keys()) {
            when (val value = json.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "slim_launcher_prefs"
        private const val HISTORY_SEPARATOR = "|"
        const val MAX_HISTORY_SIZE = 8

        const val WEATHER_OFF = "off"
        const val WEATHER_SIMULATED = "simulated"
        const val WEATHER_REAL = "real"

        /** Sentinel for [widgetId] when no widget is bound. Matches AppWidgetManager.INVALID_APPWIDGET_ID. */
        const val NO_WIDGET = -1

        private const val KEY_SHOW_CLOCK = "show_clock"
        private const val KEY_SHOW_DATE = "show_date"
        private const val KEY_24_HOUR = "use_24_hour"
        private const val KEY_WORLD_CLOCK_TZ = "world_clock_tz"
        private const val KEY_WORLD_CLOCK_LABEL = "world_clock_label"
        private const val KEY_WEATHER_MODE = "weather_mode"
        private const val KEY_WEATHER_CITY = "weather_city"
        private const val KEY_WEATHER_LAT = "weather_lat"
        private const val KEY_WEATHER_LON = "weather_lon"
        private const val KEY_WEATHER_FAHRENHEIT = "weather_fahrenheit"
        private const val KEY_WEATHER_CACHE = "weather_cache"
        private const val KEY_WEATHER_CACHE_TIME = "weather_cache_time"
        private const val KEY_SEARCH_HISTORY_ENABLED = "search_history_enabled"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_SWIPE_UP_SEARCH = "swipe_up_search"
        private const val KEY_SWIPE_DOWN_NOTIFICATIONS = "swipe_down_notifications"
        private const val KEY_COMM_NOTIFICATIONS_ONLY = "comm_notifications_only"
        const val BG_TRANSPARENT = "transparent"
        const val BG_DIMMED = "dimmed"
        const val BG_SOLID_BLACK = "solid_black"

        private const val KEY_SHOW_APP_ICONS = "show_app_icons"
        private const val KEY_BACKGROUND_MODE = "background_mode"
        private const val KEY_IMMERSIVE_MODE = "immersive_mode"
        private const val KEY_WIDGET_ID = "widget_id"
        private const val KEY_FOCUS_SCREEN = "focus_screen"
    }
}
