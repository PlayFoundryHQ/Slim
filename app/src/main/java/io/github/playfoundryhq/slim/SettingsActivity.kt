package io.github.playfoundryhq.slim

import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Slim Settings: feature toggles, weather configuration, search options,
 * gesture options, system shortcuts, and the About/Contact section.
 *
 * Reached by tapping the "Slim" entry inside the launcher's own app list.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SlimPreferences
    private lateinit var repository: AppRepository
    private val weatherService = WeatherService()

    // ---- Widget hosting ----
    // Shares WidgetHostManager.HOST_ID with MainActivity so a widget bound here
    // renders through the launcher's host. This host only allocates/binds ids;
    // MainActivity does the actual rendering.
    private val widgetManager by lazy { AppWidgetManager.getInstance(this) }
    private val widgetHost by lazy { AppWidgetHost(this, WidgetHostManager.HOST_ID) }
    // Widget id awaiting its configuration activity result (configure step).
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    // Refreshes the Widget section rows after a successful add/remove.
    private var refreshWidgetRow: (() -> Unit)? = null

    /**
     * System widget picker. ACTION_APPWIDGET_PICK binds the chosen provider to
     * our host on the user's behalf with system privileges — this is how a
     * non-system launcher hosts widgets without the signature-only
     * BIND_APPWIDGET permission. On return, run the provider's configuration
     * activity if it declares one, then persist the id.
     */
    private val widgetPickLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val id = result.data?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
            if (result.resultCode == RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val info = widgetManager.getAppWidgetInfo(id)
                if (info?.configure != null) {
                    pendingWidgetId = id
                    try {
                        // Host-driven launch handles non-exported configure activities.
                        widgetHost.startAppWidgetConfigureActivityForResult(
                            this, id, 0, REQ_CONFIGURE_WIDGET, null
                        )
                    } catch (e: Exception) {
                        // Configure activity refused to launch — keep the bound
                        // widget anyway; most render fine with defaults.
                        finishWidgetSetup(id)
                    }
                } else {
                    finishWidgetSetup(id)
                }
            } else if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // User backed out of the picker — release the reserved id.
                widgetHost.deleteAppWidgetId(id)
            }
        }

    // Default-launcher (RoleManager HOME) request — must run for-result so the
    // system sees our calling package, otherwise the dialog bails immediately.
    private val defaultHomeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // SAF pickers for settings backup/restore
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportBackup(uri)
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBackup(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = SlimPreferences(this)
        repository = AppRepository(this, AppDatabase.getDatabase(this).appDao())

        bindHomeSection()
        bindAppearanceSection()
        bindWidgetSection()
        bindWeatherSection()
        bindSearchSection()
        bindGesturesSection()
        bindNotificationsSection()
        bindBackupSection()
        bindSystemSection()
        bindAboutSection()
    }

    override fun onResume() {
        super.onResume()
        // The System-section rows reflect state that changes outside Slim
        // (in system Settings), so refresh their status every time we come back.
        refreshSystemSectionStatus()
    }

    private fun bindAppearanceSection() {
        val switchIcons = findViewById<SwitchMaterial>(R.id.switchShowAppIcons)
        switchIcons.isChecked = prefs.showAppIcons
        switchIcons.setOnCheckedChangeListener { _, checked -> prefs.showAppIcons = checked }

        // Background mode radio group
        val radioBg = findViewById<RadioGroup>(R.id.radioBackgroundMode)
        radioBg.check(when (prefs.backgroundMode) {
            SlimPreferences.BG_TRANSPARENT -> R.id.radioBgTransparent
            SlimPreferences.BG_SOLID_BLACK -> R.id.radioBgSolidBlack
            else -> R.id.radioBgDimmed
        })
        radioBg.setOnCheckedChangeListener { _, id ->
            prefs.backgroundMode = when (id) {
                R.id.radioBgTransparent -> SlimPreferences.BG_TRANSPARENT
                R.id.radioBgSolidBlack -> SlimPreferences.BG_SOLID_BLACK
                else -> SlimPreferences.BG_DIMMED
            }
        }

        val switchImmersive = findViewById<SwitchMaterial>(R.id.switchImmersiveMode)
        switchImmersive.isChecked = prefs.immersiveMode
        switchImmersive.setOnCheckedChangeListener { _, checked -> prefs.immersiveMode = checked }

        val switchFocusScreen = findViewById<SwitchMaterial>(R.id.switchFocusScreen)
        switchFocusScreen.isChecked = prefs.focusScreenEnabled
        switchFocusScreen.setOnCheckedChangeListener { _, checked -> prefs.focusScreenEnabled = checked }

        findViewById<TextView>(R.id.btnHiddenApps).setOnClickListener {
            showHiddenAppsDialog()
        }
    }

    /** Lists hidden apps; tapping one unhides it. */
    private fun showHiddenAppsDialog() {
        lifecycleScope.launch {
            val hiddenApps = repository.getHiddenApps()
            if (hiddenApps.isEmpty()) {
                Toast.makeText(this@SettingsActivity, R.string.settings_no_hidden_apps, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = hiddenApps.map { it.displayLabel }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity, R.style.Theme_Slim_Dialog)
                .setTitle(getString(R.string.settings_hidden_apps) + " — " + getString(R.string.settings_unhide_hint))
                .setItems(labels) { _, which ->
                    lifecycleScope.launch {
                        repository.setAppHidden(hiddenApps[which].id, false)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ---- Widget section ----

    private fun bindWidgetSection() {
        val btnWidget = findViewById<TextView>(R.id.btnWidget)
        val btnRemove = findViewById<TextView>(R.id.btnRemoveWidget)

        fun refresh() {
            val hasWidget = prefs.widgetId != SlimPreferences.NO_WIDGET &&
                widgetManager.getAppWidgetInfo(prefs.widgetId) != null
            btnWidget.text = getString(
                if (hasWidget) R.string.settings_change_widget else R.string.settings_add_widget
            )
            btnRemove.visibility = if (hasWidget) View.VISIBLE else View.GONE
        }
        refreshWidgetRow = ::refresh
        refresh()

        btnWidget.setOnClickListener { pickWidget() }
        btnRemove.setOnClickListener { removeWidget() }
    }

    /** Opens the system widget picker, reserving a fresh host id for the choice. */
    private fun pickWidget() {
        val id = widgetHost.allocateAppWidgetId()
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            // Empty custom lists keep some OEM pickers from crashing.
            putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, ArrayList<Parcelable>())
            putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS, ArrayList<Parcelable>())
        }
        try {
            widgetPickLauncher.launch(intent)
        } catch (e: Exception) {
            widgetHost.deleteAppWidgetId(id)
            Toast.makeText(this, R.string.settings_widget_pick_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Replaces any existing widget with [id] and persists it so MainActivity
     * renders it. Only one widget slot exists, so the previous one is released.
     */
    private fun finishWidgetSetup(id: Int) {
        // Guard against a picker that returned an unbound id (some OEM pickers
        // hand back an allocation without binding it).
        if (widgetManager.getAppWidgetInfo(id) == null) {
            widgetHost.deleteAppWidgetId(id)
            Toast.makeText(this, R.string.settings_widget_pick_failed, Toast.LENGTH_LONG).show()
            return
        }
        val previous = prefs.widgetId
        if (previous != SlimPreferences.NO_WIDGET && previous != id) {
            widgetHost.deleteAppWidgetId(previous)
        }
        prefs.widgetId = id
        refreshWidgetRow?.invoke()
        Toast.makeText(this, R.string.settings_widget_added, Toast.LENGTH_SHORT).show()
    }

    private fun removeWidget() {
        val id = prefs.widgetId
        if (id != SlimPreferences.NO_WIDGET) {
            widgetHost.deleteAppWidgetId(id)
        }
        prefs.widgetId = SlimPreferences.NO_WIDGET
        refreshWidgetRow?.invoke()
        Toast.makeText(this, R.string.settings_widget_removed, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIGURE_WIDGET) return
        val id = data?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId
        ) ?: pendingWidgetId
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // Do NOT gate on resultCode. Glance / Jetpack-Compose configuration
        // activities (GitHub's contribution widget, Slack's status & unreads
        // widgets, …) persist their own state via updateAppWidgetState and then
        // finish() *without* setResult(RESULT_OK) — so a perfectly configured
        // widget comes back as RESULT_CANCELED. The reliable signal is simply
        // whether the id is still bound to a live provider; if it is, keep it.
        if (widgetManager.getAppWidgetInfo(id) != null) {
            finishWidgetSetup(id)
        } else {
            widgetHost.deleteAppWidgetId(id)
        }
    }

    private fun bindBackupSection() {
        findViewById<TextView>(R.id.btnExportSettings).setOnClickListener {
            exportLauncher.launch("slim-backup.json")
        }
        findViewById<TextView>(R.id.btnImportSettings).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        }
    }

    /** Writes preferences + app customizations (favorites/hidden/renames) to a JSON file. */
    private fun exportBackup(uri: Uri) {
        lifecycleScope.launch {
            try {
                val backup = JSONObject()
                backup.put("preferences", prefs.exportToJson())

                val apps = JSONArray()
                for (app in repository.getCustomizedApps()) {
                    apps.put(JSONObject().apply {
                        put("id", app.id)
                        put("isFavorite", app.isFavorite)
                        put("isHidden", app.isHidden)
                        put("customLabel", app.customLabel ?: JSONObject.NULL)
                    })
                }
                backup.put("apps", apps)

                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(backup.toString(2).toByteArray())
                }
                Toast.makeText(this@SettingsActivity, R.string.settings_export_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.settings_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Restores preferences + app customizations from a backup file. */
    private fun importBackup(uri: Uri) {
        lifecycleScope.launch {
            try {
                val content = contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: throw IllegalStateException("Empty file")

                val backup = JSONObject(content)
                prefs.importFromJson(backup.getJSONObject("preferences"))

                val apps = backup.optJSONArray("apps") ?: JSONArray()
                for (i in 0 until apps.length()) {
                    val app = apps.getJSONObject(i)
                    val id = app.getString("id")
                    repository.setAppAsFavorite(id, app.optBoolean("isFavorite", false))
                    repository.setAppHidden(id, app.optBoolean("isHidden", false))
                    val label = if (app.isNull("customLabel")) null else app.getString("customLabel")
                    repository.setCustomLabel(id, label)
                }

                Toast.makeText(this@SettingsActivity, R.string.settings_import_done, Toast.LENGTH_LONG).show()
                recreate() // Reload toggles with imported values
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, R.string.settings_import_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindHomeSection() {
        val switchClock = findViewById<SwitchMaterial>(R.id.switchShowClock)
        val switchDate = findViewById<SwitchMaterial>(R.id.switchShowDate)
        val switch24h = findViewById<SwitchMaterial>(R.id.switch24Hour)
        val rowWorldClock = findViewById<TextView>(R.id.rowWorldClock)

        switchClock.isChecked = prefs.showClock
        switchDate.isChecked = prefs.showDate
        switch24h.isChecked = prefs.use24HourFormat

        switchClock.setOnCheckedChangeListener { _, checked -> prefs.showClock = checked }
        switchDate.setOnCheckedChangeListener { _, checked -> prefs.showDate = checked }
        switch24h.setOnCheckedChangeListener { _, checked -> prefs.use24HourFormat = checked }

        fun refreshWorldClockRow() {
            val label = prefs.worldClockLabel.ifEmpty { getString(R.string.settings_world_clock_off) }
            rowWorldClock.text = getString(R.string.settings_world_clock, label)
        }
        refreshWorldClockRow()
        rowWorldClock.setOnClickListener {
            val labels = mutableListOf(getString(R.string.settings_world_clock_off))
            labels.addAll(WorldClockOptions.CITIES.map { it.label })
            AlertDialog.Builder(this, R.style.Theme_Slim_Dialog)
                .setTitle(R.string.settings_world_clock_title)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == 0) {
                        prefs.worldClockTimeZoneId = ""
                        prefs.worldClockLabel = ""
                    } else {
                        val city = WorldClockOptions.CITIES[which - 1]
                        prefs.worldClockTimeZoneId = city.timeZoneId
                        prefs.worldClockLabel = city.label
                    }
                    refreshWorldClockRow()
                }
                .show()
        }
    }

    private fun bindWeatherSection() {
        val radioGroup = findViewById<RadioGroup>(R.id.radioWeatherMode)
        val cityRow = findViewById<View>(R.id.cityInputRow)
        val editCity = findViewById<EditText>(R.id.editCity)
        val btnSaveCity = findViewById<Button>(R.id.btnSaveCity)
        val switchFahrenheit = findViewById<SwitchMaterial>(R.id.switchFahrenheit)
        val privacyNote = findViewById<TextView>(R.id.txtWeatherPrivacyNote)

        // Restore current state
        val checkedId = when (prefs.weatherMode) {
            SlimPreferences.WEATHER_OFF -> R.id.radioWeatherOff
            SlimPreferences.WEATHER_REAL -> R.id.radioWeatherReal
            else -> R.id.radioWeatherSimulated
        }
        radioGroup.check(checkedId)
        editCity.setText(prefs.weatherCity)
        switchFahrenheit.isChecked = prefs.useFahrenheit

        fun updateRealWeatherVisibility(isReal: Boolean) {
            cityRow.visibility = if (isReal) View.VISIBLE else View.GONE
            privacyNote.visibility = if (isReal) View.VISIBLE else View.GONE
        }
        updateRealWeatherVisibility(prefs.weatherMode == SlimPreferences.WEATHER_REAL)

        radioGroup.setOnCheckedChangeListener { _, id ->
            prefs.weatherMode = when (id) {
                R.id.radioWeatherOff -> SlimPreferences.WEATHER_OFF
                R.id.radioWeatherReal -> SlimPreferences.WEATHER_REAL
                else -> SlimPreferences.WEATHER_SIMULATED
            }
            // Force a refresh on next launcher resume
            prefs.lastWeatherFetchTime = 0L
            updateRealWeatherVisibility(id == R.id.radioWeatherReal)
        }

        btnSaveCity.setOnClickListener {
            val city = editCity.text.toString().trim()
            if (city.isEmpty()) return@setOnClickListener
            btnSaveCity.isEnabled = false
            lifecycleScope.launch {
                val result = weatherService.geocodeCity(city)
                btnSaveCity.isEnabled = true
                if (result != null) {
                    prefs.weatherCity = result.name
                    prefs.weatherLatitude = result.latitude
                    prefs.weatherLongitude = result.longitude
                    prefs.lastWeatherFetchTime = 0L
                    editCity.setText(result.name)
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.settings_weather_city_saved, result.name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_weather_city_not_found,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        switchFahrenheit.setOnCheckedChangeListener { _, checked ->
            prefs.useFahrenheit = checked
            prefs.lastWeatherFetchTime = 0L
        }
    }

    private fun bindSearchSection() {
        val switchHistory = findViewById<SwitchMaterial>(R.id.switchSearchHistory)
        val btnClear = findViewById<TextView>(R.id.btnClearHistory)

        switchHistory.isChecked = prefs.searchHistoryEnabled
        switchHistory.setOnCheckedChangeListener { _, checked ->
            prefs.searchHistoryEnabled = checked
            if (!checked) prefs.clearSearchHistory()
        }

        btnClear.setOnClickListener {
            prefs.clearSearchHistory()
            Toast.makeText(this, R.string.settings_history_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindGesturesSection() {
        val switchSwipeUp = findViewById<SwitchMaterial>(R.id.switchSwipeUpSearch)
        switchSwipeUp.isChecked = prefs.swipeUpForSearch
        switchSwipeUp.setOnCheckedChangeListener { _, checked -> prefs.swipeUpForSearch = checked }

        val switchSwipeDown = findViewById<SwitchMaterial>(R.id.switchSwipeDownNotifications)
        switchSwipeDown.isChecked = prefs.swipeDownForNotifications
        switchSwipeDown.setOnCheckedChangeListener { _, checked ->
            prefs.swipeDownForNotifications = checked
        }
    }

    private fun bindNotificationsSection() {
        val switchCommOnly = findViewById<SwitchMaterial>(R.id.switchCommNotificationsOnly)
        switchCommOnly.isChecked = prefs.communicationNotificationsOnly
        switchCommOnly.setOnCheckedChangeListener { _, checked ->
            prefs.communicationNotificationsOnly = checked
        }
    }

    // BatteryLife: the Play Store restricts REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    // but Slim ships via GitHub Releases and F-Droid, and for a home-replacement
    // app that an OEM freezer suspends this exemption is a legitimate, on-request
    // fix — never asked for automatically.
    @android.annotation.SuppressLint("BatteryLife")
    private fun bindSystemSection() {
        findViewById<TextView>(R.id.btnDefaultLauncher).setOnClickListener {
            defaultHomeLauncher.launch(DefaultLauncherHelper.requestIntent(this))
        }
        findViewById<TextView>(R.id.btnNotificationAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, R.string.settings_notification_hint, Toast.LENGTH_LONG).show()
        }
        findViewById<TextView>(R.id.btnBatteryOptimization).setOnClickListener {
            if (isIgnoringBatteryOptimizations()) {
                Toast.makeText(this, R.string.settings_battery_optimization_done, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // The direct one-tap dialog (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            // fall back to the full battery-optimization list if the OEM blocks it.
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            try {
                startActivity(direct)
            } catch (e: Exception) {
                try {
                    startActivity(list)
                    Toast.makeText(this, R.string.settings_battery_optimization_hint, Toast.LENGTH_LONG).show()
                } catch (e2: Exception) {
                    Toast.makeText(this, R.string.app_info_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        refreshSystemSectionStatus()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** Appends a live " · on/off" badge to each System-section row. */
    private fun refreshSystemSectionStatus() {
        fun badge(on: Boolean) =
            getString(if (on) R.string.settings_status_on else R.string.settings_status_off)

        findViewById<TextView>(R.id.btnDefaultLauncher).text =
            getString(R.string.settings_choose_home) + badge(DefaultLauncherHelper.isDefaultHome(this))

        val listenerEnabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        )?.contains(packageName) == true
        findViewById<TextView>(R.id.btnNotificationAccess).text =
            getString(R.string.settings_notification_permission) + badge(listenerEnabled)

        findViewById<TextView>(R.id.btnBatteryOptimization).text =
            getString(R.string.settings_battery_optimization) + badge(isIgnoringBatteryOptimizations())
    }

    private fun bindAboutSection() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.txtVersion).text = getString(R.string.about_version, versionName)

        findViewById<TextView>(R.id.btnGithub).setOnClickListener {
            openUrl(getString(R.string.url_github))
        }
        findViewById<TextView>(R.id.btnWebsite).setOnClickListener {
            openUrl(getString(R.string.url_website))
        }
        findViewById<TextView>(R.id.btnReportBug).setOnClickListener {
            openUrl(getString(R.string.url_issues))
        }
        findViewById<TextView>(R.id.btnContact).setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:" + getString(R.string.contact_email))
                putExtra(Intent.EXTRA_SUBJECT, "Slim Launcher feedback")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.contact_email), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        // Request code for the widget provider's configuration activity, launched
        // via AppWidgetHost.startAppWidgetConfigureActivityForResult.
        private const val REQ_CONFIGURE_WIDGET = 1011
    }
}
