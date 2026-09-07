package io.github.playfoundryhq.slim

import android.app.AlertDialog
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.BatteryManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), WaveGestureView.OnLetterSelectedListener, NotificationRegistry.NotificationUpdateListener {

    companion object {
        private const val SETTINGS_LETTER = "⚙"
        // Minimum gap between real-weather fetch attempts, including failed ones.
        // The clock ticks every second and used to re-fire a request on every
        // tick until one succeeded — that self-inflicted hammering is what
        // tripped Open-Meteo's "too many requests" limit and left it stuck.
        private const val WEATHER_RETRY_INTERVAL_MS = 60_000L
        // Minimum gap between onResume-triggered app-list refreshes.
        private const val RESUME_REFRESH_INTERVAL_MS = 60_000L
    }

    private lateinit var appRecyclerView: RecyclerView
    private lateinit var headerLayout: View
    private lateinit var searchResultsRecyclerView: RecyclerView
    private lateinit var waveGestureView: WaveGestureView
    private lateinit var searchEditText: EditText
    private lateinit var searchPanel: View
    private lateinit var searchScrim: View
    private lateinit var historyScroll: HorizontalScrollView
    private lateinit var txtNoResults: TextView
    private lateinit var txtClock: TextView
    private lateinit var txtWorldClock: TextView
    private lateinit var txtDate: TextView
    private lateinit var txtWeather: TextView
    private lateinit var txtLetterPopup: TextView
    private lateinit var statusInfoRow: View
    private lateinit var txtNotificationSummary: TextView
    private lateinit var txtBattery: TextView
    private lateinit var focusVeil: View
    private lateinit var txtFocusClock: TextView
    private lateinit var txtFocusDate: TextView
    private lateinit var txtFocusNotif: TextView
    private var packageChangeReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var batteryReceiverRegistered = false
    private lateinit var widgetHost: WidgetHostManager

    private lateinit var db: AppDatabase
    private lateinit var repository: AppRepository
    private lateinit var prefs: SlimPreferences
    private lateinit var adapter: AppListAdapter
    private lateinit var searchAdapter: AppListAdapter
    private val weatherService = WeatherService()

    private var allApps = listOf<AppItem>()
    private var favorites = listOf<AppItem>()
    private var filteredList = listOf<AdapterItem>()

    private lateinit var gestureDetector: GestureDetector

    // State controlling list visibility
    private var isAlphabetScrubbing = false
    // Set on ACTION_DOWN when the touch starts on the alphabet index so the
    // swipe-up-for-search gesture never fires while scrubbing letters.
    private var touchStartedOnWave = false
    // Track last down position for reliable swipe detection.
    // GestureDetector.onFling is unreliable when RecyclerView consumes events.
    private var lastDownX = 0f
    private var lastDownY = 0f
    // Actual height of the system gesture nav zone — read from window insets
    private var systemGestureHeight = 0
    private var weatherFetchInProgress = false
    // Swipe thresholds, computed once from density so gesture feel is consistent
    // across screen densities instead of the raw px constants this used to be
    // hardcoded to (which were tuned on one density and drifted on others).
    private var swipeDistanceThresholdPx = 0f
    private var swipeVelocityThresholdPxPerSec = 0f
    private var swipeUpDistanceThresholdPx = 0f
    // Prompt to become the default launcher at most once per process so we
    // nudge after a fresh install without nagging on every resume.
    private var defaultHomePrompted = false
    // Whether the IME was open when onPause() fired — used to restore keyboard
    // state on resume only when the screen turned off (not after Home presses).
    private var imeWasOpenBeforePause = false
    // The RoleManager HOME request must run for-result so the system can read
    // our calling package; plain startActivity() gives it a null caller and the
    // dialog bails instantly. The result is ignored — onResume re-checks state.
    private val defaultHomeLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { }
    // Last time we *attempted* a real-weather fetch (success or failure), used
    // to throttle retries so a failing endpoint doesn't get hammered.
    private var lastWeatherAttemptTime = 0L
    // Last time onResume kicked a full app refresh. Package install/remove comes
    // in through PackageChangeReceiver, so the only job of the onResume refresh
    // is to catch the rare case the broadcast was missed (Slim not registered
    // yet during a fast install). Doing the refresh + its two retries on every
    // single Home press was a LauncherApps query storm on the hot resume path.
    private var lastResumeRefreshTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val returnToFavoritesRunnable = Runnable {
        exitAlphabetMode()
    }
    // Retry runnables for app-refresh (F-Droid/Revanced install timing).
    // Each fires once; retry2 is the final attempt — no infinite loop.
    private var refreshRetryCount = 0
    private val refreshRetry1 = Runnable {
        refreshRetryCount++
        lifecycleScope.launch { repository.refreshApps() }
    }
    private val refreshRetry2 = Runnable {
        refreshRetryCount++
        lifecycleScope.launch { repository.refreshApps() }
    }
    // Cached so the clock tick doesn't allocate two new SimpleDateFormat objects every second.
    private val clockDateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
    private val clockTime24Format = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockTime12Format = SimpleDateFormat("h:mm a", Locale.getDefault())
    // Secondary "world clock" formatters — same patterns as above but pinned to
    // the configured timezone instead of the device default. Rebuilt only when
    // the configured timezone changes (in applyHeaderPreferences), not per tick.
    private val worldClockTime24Format = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val worldClockTime12Format = SimpleDateFormat("h:mm a", Locale.getDefault())
    private var appliedWorldClockTimeZoneId = ""
    private var clockTicks = 0
    // Debounce adapter rebuilds triggered by rapid notification events (e.g. music player).
    private val pendingAdapterUpdate = Runnable { updateAdapterData() }
    // Self-rescheduling clock tick — started in onStart, stopped in onStop, so it
    // never runs (and never competes with input on the main thread) while Slim is
    // backgrounded. Previously a java.util.Timer that ran for the entire process
    // lifetime regardless of visibility, which is wasteful for a launcher that can
    // sit backgrounded for days and showed up as main-thread jank ("Slow UI thread")
    // in dumpsys gfxinfo.
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            txtClock.text = (if (prefs.use24HourFormat) clockTime24Format else clockTime12Format).format(now)
            txtDate.text = clockDateFormat.format(now)
            updateWorldClockText()
            updateFocusScreenText()
            // Weather has its own 60s cache; no need to check on every tick.
            if (++clockTicks % 60 == 0) updateWeather()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        appRecyclerView = findViewById(R.id.appRecyclerView)
        headerLayout = findViewById(R.id.headerLayout)
        searchResultsRecyclerView = findViewById(R.id.searchResultsRecyclerView)
        waveGestureView = findViewById(R.id.waveGestureView)
        searchEditText = findViewById(R.id.searchEditText)
        searchPanel = findViewById(R.id.searchPanel)
        searchScrim = findViewById(R.id.searchScrim)
        historyScroll = findViewById(R.id.historyScroll)
        txtNoResults = findViewById(R.id.txtNoResults)
        txtClock = findViewById(R.id.txtClock)
        txtWorldClock = findViewById(R.id.txtWorldClock)
        txtDate = findViewById(R.id.txtDate)
        txtWeather = findViewById(R.id.txtWeather)
        txtLetterPopup = findViewById(R.id.txtLetterPopup)
        statusInfoRow = findViewById(R.id.statusInfoRow)
        txtNotificationSummary = findViewById(R.id.txtNotificationCount)
        txtBattery = findViewById(R.id.txtBattery)
        focusVeil = findViewById(R.id.focusVeil)
        txtFocusClock = findViewById(R.id.txtFocusClock)
        txtFocusDate = findViewById(R.id.txtFocusDate)
        txtFocusNotif = findViewById(R.id.txtFocusNotif)
        setupFocusScreen()

        setupWindowInsets()

        // Original thresholds (120px / 80px-per-sec / 60px) were tuned at ~3x
        // density and hardcoded — re-derive them as dp so feel is consistent
        // across devices.
        val density = resources.displayMetrics.density
        swipeDistanceThresholdPx = 40f * density
        swipeVelocityThresholdPxPerSec = 27f * density
        swipeUpDistanceThresholdPx = 20f * density

        // Initialize Database, Repository & Preferences
        db = AppDatabase.getDatabase(this)
        repository = AppRepository(this, db.appDao())
        prefs = SlimPreferences(this)

        // Host for an optional single home-screen widget (added via Settings).
        widgetHost = WidgetHostManager(this, findViewById(R.id.widgetContainer), prefs)

        // Set up home RecyclerView (Favorites + alphabetical browsing)
        adapter = AppListAdapter(this, emptyList()) { appItem, isLongClick ->
            if (isLongClick) {
                showAppOptionsDialog(appItem)
            } else {
                onAppClicked(appItem, fromSearch = false)
            }
        }
        appRecyclerView.layoutManager = LinearLayoutManager(this)
        appRecyclerView.adapter = adapter
        // The default ItemAnimator runs its own alpha fade on every item-change
        // update (which fires often here: notification events, icon/color
        // toggles, app refreshes) and resets alpha to 1 when it finishes —
        // clobbering the Favorites fade-downward effect set in onBindViewHolder
        // moments after it's applied. Disabling it isn't a loss for this list;
        // it never had its own row-add/remove flourish to begin with.
        appRecyclerView.itemAnimator = null

        // Set up search results RecyclerView (inside the floating search panel)
        searchAdapter = AppListAdapter(this, emptyList()) { appItem, isLongClick ->
            if (isLongClick) {
                showAppOptionsDialog(appItem)
            } else {
                onAppClicked(appItem, fromSearch = true)
            }
        }
        searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        searchResultsRecyclerView.adapter = searchAdapter

        // Bind Custom Listeners
        waveGestureView.listener = this
        NotificationRegistry.registerListener(this)

        // The weather chip is purely informational — tapping it does nothing.
        // (It used to jump into Settings, which was an easy mis-tap.)

        // Tapping the header notification bell opens the system shade
        txtNotificationSummary.setOnClickListener {
            expandNotificationShade()
        }

        // Search functional trigger
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Setup Swipe-Up and swipe-left/right GestureDetector
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // Use tracked down position as fallback when e1 is null
                // (can happen during RecyclerView layout passes)
                val downX = e1?.x ?: lastDownX
                val downY = e1?.y ?: lastDownY
                val xDiff = e2.x - downX
                val yDiff = downY - e2.y

                // Swipe up opens search — only from the home (favorites) state,
                // never while browsing the alphabetical list or scrubbing.
                if (yDiff > swipeDistanceThresholdPx && Math.abs(velocityY) > swipeVelocityThresholdPxPerSec) {
                    if (prefs.swipeUpForSearch && !isAlphabetScrubbing) {
                        showSearchBar()
                        return true
                    }
                }
                // Swipe down opens the system notification shade
                if (yDiff < -swipeDistanceThresholdPx && Math.abs(velocityY) > swipeVelocityThresholdPxPerSec) {
                    if (prefs.swipeDownForNotifications && !isAlphabetScrubbing &&
                        searchPanel.visibility != View.VISIBLE
                    ) {
                        expandNotificationShade()
                        return true
                    }
                }
                // Horizontal swipes exit alphabet apps view back to favorites list
                if (Math.abs(xDiff) > swipeDistanceThresholdPx && Math.abs(velocityX) > swipeVelocityThresholdPxPerSec) {
                    if (isAlphabetScrubbing) {
                        exitAlphabetMode()
                        return true
                    }
                }
                return false
            }
        })

        // Back handling — a launcher's home is the bottom of the navigation
        // stack, so Back must NEVER finish it (finishing yields the screen to
        // whatever other home activity the system falls back to, which is the
        // "Slim sometimes drops to the stock launcher on back/back/back" bug).
        // An always-enabled callback only closes our own overlays and otherwise
        // consumes the event. Using OnBackPressedDispatcher also makes this
        // correct under predictive-back gestures, not just the Back key.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    focusVeil.visibility == View.VISIBLE -> dismissFocusScreen()
                    searchPanel.visibility == View.VISIBLE -> hideSearchBar()
                    isAlphabetScrubbing -> exitAlphabetMode()
                    else -> { /* At home root: consume and stay. */ }
                }
            }
        })

        // Clock & Weather Update Loops — actually started/stopped in onStart/onStop
        // so they don't tick while backgrounded.

        // Load Apps and start Flow Collection
        lifecycleScope.launch {
            repository.refreshApps()

            // Observe cached apps (sorted by display name so renames re-sort correctly)
            launch {
                repository.allAppsFlow.collectLatest { apps ->
                    allApps = apps.sortedBy { it.displayLabel.lowercase(Locale.getDefault()) }
                    updateAlphabetLetters()
                    updateAdapterData()
                }
            }

            // Observe favorites
            launch {
                repository.favoritesFlow.collectLatest { favs ->
                    favorites = favs
                    updateAdapterData()
                }
            }
        }

        // Register for package install/remove broadcasts so newly installed
        // apps appear immediately without waiting for the next onResume cycle.
        val receiver = PackageChangeReceiver { scheduleAppRefresh() }
        packageChangeReceiver = receiver
        registerReceiver(receiver, PackageChangeReceiver.createIntentFilter())
    }

    /**
     * The focus screen is a plain black View inside our own window — no window
     * flags, no new Activity, no keyguard interaction, no service. It is a
     * deliberate "blank everything down to a clock" toggle: long-press the
     * clock to raise it, tap or swipe up to dismiss.
     *
     * It is intentionally NOT tied to screen-off / unlock. A launcher cannot
     * reliably paint anything at wake time — the OS may have killed Slim while
     * the screen was off, and it certainly cannot draw over the secure
     * keyguard. A gesture the user performs while Slim is already foreground is
     * the only version of this idea that always works.
     */
    private fun setupFocusScreen() {
        txtClock.setOnLongClickListener {
            if (prefs.focusScreenEnabled) {
                showFocusScreen()
                true
            } else {
                false
            }
        }
        val veilGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                dismissFocusScreen(); return true
            }
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if ((e1?.y ?: 0f) - e2.y > swipeUpDistanceThresholdPx) {
                    dismissFocusScreen(); return true
                }
                return false
            }
        })
        focusVeil.setOnTouchListener { v, ev ->
            veilGestures.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun showFocusScreen() {
        if (focusVeil.visibility == View.VISIBLE) return
        updateFocusScreenText()
        focusVeil.alpha = 1f
        focusVeil.visibility = View.VISIBLE
    }

    private fun dismissFocusScreen() {
        if (focusVeil.visibility != View.VISIBLE) return
        focusVeil.animate().alpha(0f).setDuration(200)
            .withEndAction { focusVeil.visibility = View.GONE }
            .start()
    }

    private fun updateFocusScreenText() {
        if (focusVeil.visibility != View.VISIBLE) return
        val now = Date()
        txtFocusClock.text =
            (if (prefs.use24HourFormat) clockTime24Format else clockTime12Format).format(now)
        txtFocusDate.visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        txtFocusDate.text = clockDateFormat.format(now)
        val count = NotificationRegistry.getNotificationCount()
        if (count > 0) {
            txtFocusNotif.visibility = View.VISIBLE
            txtFocusNotif.text = "🔔 $count"
        } else {
            txtFocusNotif.visibility = View.GONE
        }
    }

    /**
     * A HOME intent redelivered to the already-running launcher (Home pressed
     * while Slim is foreground). singleTask means this arrives here rather than
     * as a fresh onCreate, so the "return to a clean home" reset has to live
     * here — onResume alone left the alphabet list scrolled wherever the user
     * last scrubbed to.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // onNewIntent fires before onResume on a singleTask relaunch. onPause has
        // already run and, if search was open with the keyboard up, set
        // imeWasOpenBeforePause = true — which would make onResume re-summon the
        // keyboard over the now-empty home. Clear it and force the panel closed
        // synchronously so onResume's "panel still visible?" check is false.
        imeWasOpenBeforePause = false
        dismissFocusScreen()  // Home press always returns to the plain app list
        if (searchPanel.visibility == View.VISIBLE) hideSearchBar()
        searchPanel.visibility = View.GONE
        if (isAlphabetScrubbing) exitAlphabetMode() else appRecyclerView.scrollToPosition(0)
    }

    /**
     * Draws the launcher edge-to-edge so our window background owns the entire
     * screen — crucially the status-bar strip. Without this, immersive mode
     * hides the status bar but the window doesn't extend into that area, so the
     * wallpaper shows through as a bright empty band above the (black) content.
     *
     * To keep the layout correct in both modes we re-apply the live system-bar
     * insets as padding: the header clears the status bar when it's visible and
     * the app list clears the navigation bar. When immersive hides the status
     * bar its top inset becomes 0, so the content simply fills the freed strip.
     */
    private fun setupWindowInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Pre-R: keep the legacy fullscreen flags (set in applyImmersiveMode)
            // and a safe gesture-zone fallback.
            systemGestureHeight = (64 * resources.displayMetrics.density).toInt()
            return
        }
        window.setDecorFitsSystemWindows(false)
        val baseHeaderTop = headerLayout.paddingTop
        val baseListBottom = appRecyclerView.paddingBottom
        val baseStatusRowTop = statusInfoRow.paddingTop
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            val gestureInsets = insets.getInsets(android.view.WindowInsets.Type.systemGestures())
            // Add a small buffer so even near-miss swipes don't fight the system.
            systemGestureHeight = (gestureInsets.bottom * 1.3f).toInt()
            if (systemGestureHeight == 0) {
                systemGestureHeight = (64 * resources.displayMetrics.density).toInt()
            }
            headerLayout.setPadding(
                headerLayout.paddingLeft, baseHeaderTop + bars.top,
                headerLayout.paddingRight, headerLayout.paddingBottom
            )
            appRecyclerView.setPadding(
                appRecyclerView.paddingLeft, appRecyclerView.paddingTop,
                appRecyclerView.paddingRight, baseListBottom + bars.bottom
            )
            // Corner status row (notification/battery) clears the status bar the
            // same way the header does, even though it's a separate view now.
            statusInfoRow.setPadding(
                statusInfoRow.paddingLeft, baseStatusRowTop + bars.top,
                statusInfoRow.paddingRight, statusInfoRow.paddingBottom
            )
            view.onApplyWindowInsets(insets)
        }
    }

    /**
     * Re-assert immersive mode whenever we regain focus. The system tends to
     * restore the status bar after dialogs, the notification shade, app
     * switches, etc., so a launcher has to re-hide it on focus or it silently
     * creeps back — which made immersive mode feel fragile.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (::prefs.isInitialized && prefs.immersiveMode &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ) {
                window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
            }
        } else {
            // When any floating window takes focus (overlay, dialog, system prompt,
            // etc.) it can swallow the ACTION_UP that would normally end a gesture.
            // Cancel any in-flight scrub so WaveGestureView never gets stuck in
            // isDragging=true waiting for a terminal touch event that won't come.
            waveGestureView.cancelDrag()
        }
    }

    /**
     * If Slim isn't the default Home app (e.g. right after an install, which
     * wipes the assignment), show the one-tap RoleManager dialog to reclaim it.
     * Fires at most once per process so a declined prompt doesn't keep popping.
     *
     * All work runs on IO: PackageManager.resolveActivity(), RoleManager.isRoleAvailable(),
     * and RoleManager.isRoleHeld() are Binder IPC calls that can stall for seconds on
     * some devices. Calling them synchronously in onResume() was blocking the main
     * thread long enough to trigger "Application does not have a focused window" ANRs.
     */
    private fun maybePromptDefaultLauncher() {
        if (defaultHomePrompted) return
        defaultHomePrompted = true  // set before the coroutine to prevent double-fire
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                if (DefaultLauncherHelper.isDefaultHome(this@MainActivity)) return@launch
                val intent = DefaultLauncherHelper.requestIntent(this@MainActivity)
                withContext(Dispatchers.Main) {
                    defaultHomeLauncher.launch(intent)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
        handler.removeCallbacks(clockTickRunnable)
        handler.post(clockTickRunnable)
    }

    override fun onPause() {
        super.onPause()
        // Capture whether the keyboard was open before we tear it down.
        // onResume uses this to distinguish "screen-off while searching"
        // (restore keyboard) from "user pressed Home" (leave search bar empty).
        imeWasOpenBeforePause =
            searchPanel.visibility == View.VISIBLE && searchEditText.hasFocus()
        // Release the IME input connection before losing the window. If the keyboard
        // is open (or was recently open) and the user presses home/power, the IME
        // holds a stale input token that races with focus re-acquisition on the next
        // resume — the mismatch between InputFlinger's IME focus and WM's window focus
        // causes "Application does not have a focused window" ANRs.
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        currentFocus?.clearFocus()
    }

    override fun onStop() {
        super.onStop()
        widgetHost.stopListening()
        handler.removeCallbacks(clockTickRunnable)
    }

    override fun onResume() {
        super.onResume()
        maybePromptDefaultLauncher()
        // Re-render in case a widget was added/removed/changed in Settings.
        widgetHost.render()
        applyHeaderPreferences()
        applyAdaptiveColors()
        applyBackgroundMode()
        applyImmersiveMode()
        // Appearance / weather settings may have changed in Settings
        adapter.setShowIcons(prefs.showAppIcons)
        searchAdapter.setShowIcons(prefs.showAppIcons)
        updateWeather()
        // Pick up newly installed/removed apps — with retry for timing (F-Droid etc.).
        // Throttled: PackageChangeReceiver is the real-time path, this is only a
        // safety net, so it need not fire on every Home press.
        val now = System.currentTimeMillis()
        if (now - lastResumeRefreshTime > RESUME_REFRESH_INTERVAL_MS) {
            lastResumeRefreshTime = now
            scheduleAppRefresh()
        }

        // Restore search-panel state after a pause.
        //
        // Case 1 — Screen turned off / power button while keyboard was open
        //          (imeWasOpenBeforePause == true):
        //          The panel is still visible but the IME was torn down.
        //          Re-request focus and show the keyboard once the window is
        //          fully resumed.  The post{} defers to the next frame so
        //          the window's focus token has time to settle — firing
        //          immediately can still race with WM on aggressive OEM ROMs
        //          (ColorOS / Oplus Hans).
        //
        // Case 2 — User pressed Home while search panel was open
        //          (imeWasOpenBeforePause == false):
        //          The panel should not stay visible with no keyboard.
        //          Dismiss it silently so the user returns to a clean home
        //          state.
        if (searchPanel.visibility == View.VISIBLE) {
            if (imeWasOpenBeforePause) {
                searchEditText.post {
                    searchEditText.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
                }
            } else {
                // Home press left the panel dangling — reset silently.
                searchPanel.visibility = View.GONE
                searchEditText.setText("")
            }
        }

        // Battery level receiver for real-time updates (register once, not on every resume)
        if (prefs.immersiveMode && !batteryReceiverRegistered) {
            if (batteryReceiver == null) {
                batteryReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        updateBatteryLevel()
                    }
                }
            }
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiverRegistered = true
        }
    }

    /**
     * Refreshes the app list now, then retries after 800ms and 2500ms.
     * Some installers (F-Droid, Revanced Manager) finish writing the APK
     * before the system registers the launcher activity — the retries
     * catch apps that weren't visible yet on the first attempt.
     * Retries fire ONCE each (no infinite loop) to avoid spamming
     * notifyDataSetChanged and breaking swipe gestures.
     */
    private fun scheduleAppRefresh() {
        // Cancel any pending retries (call-site may have changed)
        handler.removeCallbacks(refreshRetry1)
        handler.removeCallbacks(refreshRetry2)
        refreshRetryCount = 0

        lifecycleScope.launch {
            repository.refreshApps()
        }
        // Retry after 800ms and 2500ms — each fires exactly once
        handler.postDelayed(refreshRetry1, 800)
        handler.postDelayed(refreshRetry2, 2500)
    }

    // Unified app click handling for both the home list and search results
    private fun onAppClicked(appItem: AppItem, fromSearch: Boolean) {
        // Tapping Slim's own entry opens Slim Settings instead of relaunching the launcher
        if (appItem.packageName == packageName) {
            hideSearchBar()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        if (fromSearch) {
            prefs.addToSearchHistory(appItem.id)
        }
        lifecycleScope.launch {
            repository.recordAppLaunch(appItem.id)
        }
        repository.launchApp(appItem)

        // Return home layout to Favorites immediately on launch
        exitAlphabetMode()
        hideSearchBar()
    }

    // ---- Header: clock, date, weather ----

    private fun applyHeaderPreferences() {
        txtClock.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
        txtDate.visibility = if (prefs.showDate) View.VISIBLE else View.GONE
        txtWeather.visibility =
            if (prefs.weatherMode == SlimPreferences.WEATHER_OFF) View.GONE else View.VISIBLE

        val worldTz = prefs.worldClockTimeZoneId
        if (worldTz != appliedWorldClockTimeZoneId) {
            appliedWorldClockTimeZoneId = worldTz
            if (worldTz.isNotEmpty()) {
                val tz = TimeZone.getTimeZone(worldTz)
                worldClockTime24Format.timeZone = tz
                worldClockTime12Format.timeZone = tz
            }
        }
        txtWorldClock.visibility =
            if (prefs.showClock && worldTz.isNotEmpty()) View.VISIBLE else View.GONE
        updateWorldClockText()
    }

    private fun updateWorldClockText() {
        if (txtWorldClock.visibility != View.VISIBLE) return
        val now = Date()
        val time = (if (prefs.use24HourFormat) worldClockTime24Format else worldClockTime12Format).format(now)
        txtWorldClock.text = "$time · ${prefs.worldClockLabel}"
    }

    private fun updateWeather() {
        when (prefs.weatherMode) {
            SlimPreferences.WEATHER_OFF -> {
                txtWeather.visibility = View.GONE
            }
            SlimPreferences.WEATHER_REAL -> {
                txtWeather.visibility = View.VISIBLE
                val cached = prefs.lastWeatherText
                val cityConfigured = !prefs.weatherLatitude.isNaN() && !prefs.weatherLongitude.isNaN()
                // Only prompt "Set city" when no city is actually configured.
                // When a city is set but the forecast hasn't arrived yet (pending
                // or a transient fetch failure), show the city name instead of
                // misleadingly telling the user to set a city they already set.
                txtWeather.text = when {
                    cached.isNotEmpty() -> cached
                    cityConfigured -> "📍 ${prefs.weatherCity}"
                    else -> "📍 Set city"
                }
                maybeRefreshRealWeather()
            }
            else -> {
                // Ambient (offline) mode: a soft seasonal estimate, clearly not real data
                txtWeather.visibility = View.VISIBLE
                txtWeather.text = simulatedWeather()
            }
        }
    }

    // Dynamic, Offline-First Soft Weather Generator
    private fun simulatedWeather(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val month = calendar.get(Calendar.MONTH)

        val (emoji, temp) = when (month) {
            Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> { // Winter
                if (hour in 7..18) Pair("☁️", "1°C") else Pair("❄️", "-2°C")
            }
            in Calendar.MARCH..Calendar.MAY -> { // Spring
                if (hour in 7..18) Pair("⛅", "13°C") else Pair("🌙", "6°C")
            }
            in Calendar.JUNE..Calendar.AUGUST -> { // Summer
                if (hour in 7..18) Pair("☀️", "22°C") else Pair("🌙", "15°C")
            }
            else -> { // Autumn
                if (hour in 7..18) Pair("🍂", "10°C") else Pair("☁️", "5°C")
            }
        }
        return "$emoji $temp"
    }

    private fun maybeRefreshRealWeather() {
        if (weatherFetchInProgress) return
        val lat = prefs.weatherLatitude
        val lon = prefs.weatherLongitude
        if (lat.isNaN() || lon.isNaN()) return // No city configured yet

        val now = System.currentTimeMillis()
        // Skip if we already have weather that's still fresh.
        val haveFreshCache = prefs.lastWeatherText.isNotEmpty() &&
            (now - prefs.lastWeatherFetchTime) < WeatherService.REFRESH_INTERVAL_MS
        if (haveFreshCache) return
        // Throttle attempts (successes and failures alike) so the per-second
        // clock tick can't spin up back-to-back requests.
        if (now - lastWeatherAttemptTime < WEATHER_RETRY_INTERVAL_MS) return
        lastWeatherAttemptTime = now

        weatherFetchInProgress = true
        lifecycleScope.launch {
            val weather = weatherService.fetchCurrentWeather(lat, lon, prefs.useFahrenheit)
            if (weather != null) {
                val unit = if (prefs.useFahrenheit) "°F" else "°C"
                // The upcoming-change hint only appends when something's actually
                // coming (see WeatherService.findUpcomingChange) — the chip stays
                // exactly as terse as before the rest of the time.
                val changeNote = weather.upcomingChange?.let { " · $it" } ?: ""
                val text = "${weather.emoji()} ${weather.temperature.roundToInt()}$unit · ${weather.description()}$changeNote"
                prefs.lastWeatherText = text
                prefs.lastWeatherFetchTime = System.currentTimeMillis()
                txtWeather.text = text
            }
            weatherFetchInProgress = false
        }
    }

    /**
     * Expands the system notification shade. Uses the StatusBarManager
     * reflection approach common to FOSS launchers; silently no-ops on
     * devices/versions where the hidden API is blocked.
     */
    @android.annotation.SuppressLint("WrongConstant", "PrivateApi")
    private fun expandNotificationShade() {
        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val expandMethod = statusBarManager.getMethod("expandNotificationsPanel")
            expandMethod.invoke(statusBarService)
        } catch (e: Exception) {
            // Hidden API unavailable on this device — gesture does nothing
        }
    }

    // ---- Adaptive colors (readable on any wallpaper) ----

    /**
     * WallpaperManager.getWallpaperColors() is a Binder IPC call that can stall
     * the main thread for multiple seconds on some devices (observed on Oplus).
     * Do the query on IO, then apply the resolved palette on the main thread.
     */
    private fun applyAdaptiveColors() {
        lifecycleScope.launch {
            val useDarkText = withContext(Dispatchers.IO) {
                var dark = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    try {
                        val wallpaperColors = WallpaperManager.getInstance(this@MainActivity)
                            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                        if (wallpaperColors != null) {
                            dark = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                (wallpaperColors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
                            } else {
                                ColorUtils.calculateLuminance(wallpaperColors.primaryColor.toArgb()) > 0.5
                            }
                        }
                    } catch (_: Exception) { }
                }
                dark
            }
            val primary = getColor(if (useDarkText) R.color.text_primary_on_light else R.color.text_primary)
            val secondary = getColor(if (useDarkText) R.color.text_secondary_on_light else R.color.text_secondary)
            val muted = getColor(if (useDarkText) R.color.text_muted_on_light else R.color.text_muted)
            val accent = resolveAccentColor()

            txtClock.setTextColor(primary)
            txtWorldClock.setTextColor(secondary)
            txtDate.setTextColor(secondary)
            txtWeather.setTextColor(accent)
            waveGestureView.setPalette(primary, muted)
            adapter.setTextColors(primary, secondary, accent)
            searchAdapter.setTextColors(
                getColor(R.color.text_primary),
                getColor(R.color.text_secondary),
                accent
            )
        }
    }

    /** Material You dynamic accent on Android 12+, indigo fallback below. */
    private fun resolveAccentColor(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getColor(android.R.color.system_accent1_200)
        } else {
            getColor(R.color.accent_indigo)
        }
    }

    // ---- Touch & gestures ----

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // While the focus screen is up it owns every touch — let it route to the
        // veil's own OnTouchListener (tap / swipe-up to dismiss) and skip the
        // home-screen gesture processing below, so dismissing it doesn't also
        // fire swipe-up-for-search on the list underneath.
        if (focusVeil.visibility == View.VISIBLE) return super.dispatchTouchEvent(ev)
        if (ev.action == MotionEvent.ACTION_DOWN) {
            lastDownX = ev.getX(0)
            lastDownY = ev.getY(0)
            touchStartedOnWave = ev.x >= waveGestureView.left &&
                ev.y >= waveGestureView.top && ev.y <= waveGestureView.bottom
            resetInactivityTimer()
        }

        // Reliable swipe-up: pure distance check, no velocity gate.
        // Uses actual system gesture nav height so we never fight Android's
        // home/recent-apps gestures. Swipes from the middle of the screen always work.
        if (ev.action == MotionEvent.ACTION_UP && !touchStartedOnWave) {
            val inSystemGestureZone = lastDownY > resources.displayMetrics.heightPixels - systemGestureHeight
            val dy = lastDownY - ev.getY(0)
            if (!inSystemGestureZone && dy > swipeUpDistanceThresholdPx && prefs.swipeUpForSearch && !isAlphabetScrubbing
                && searchPanel.visibility != View.VISIBLE) {
                showSearchBar()
            }
        }

        // Still pass events to GestureDetector for swipe-down (notifications)
        // and horizontal swipe (exit alphabet mode).
        if (!touchStartedOnWave) {
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(returnToFavoritesRunnable)
        if (isAlphabetScrubbing && searchEditText.text.isEmpty()) {
            // Auto return to favorites only after 6 seconds of zero touch activity
            handler.postDelayed(returnToFavoritesRunnable, 6000)
        }
    }

    private fun exitAlphabetMode() {
        handler.removeCallbacks(returnToFavoritesRunnable)
        isAlphabetScrubbing = false
        updateAdapterData()
        appRecyclerView.scrollToPosition(0)
    }

    // ---- Search panel ----

    private fun showSearchBar() {
        if (searchPanel.visibility == View.GONE) {
            // Scrim fade-in
            searchScrim.alpha = 0f
            searchScrim.visibility = View.VISIBLE
            searchScrim.animate().alpha(1f).setDuration(220).start()
            // Panel scale in
            searchPanel.scaleX = 0.9f
            searchPanel.scaleY = 0.9f
            searchPanel.alpha = 0f
            searchPanel.visibility = View.VISIBLE
            showRecentApps()
            // Defer requestFocus + keyboard to after the animation completes.
            // Calling requestFocus() mid-animation races with WM's focus-token
            // assignment during resume transitions — the input system sees a
            // stale token and the keyboard silently no-shows (or worse, the
            // "no focused window" ANR fires).  Moving both into withEndAction
            // ensures the window has settled before we claim input focus.
            searchPanel.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(280)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                .withEndAction {
                    searchEditText.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
                }
                .start()
        }
    }

    private fun hideSearchBar() {
        if (searchPanel.visibility == View.VISIBLE) {
            searchScrim.animate().alpha(0f).setDuration(180)
                .withEndAction { searchScrim.visibility = View.GONE }.start()
            searchPanel.animate()
                .scaleX(0.92f).scaleY(0.92f).alpha(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { searchPanel.visibility = View.GONE }
                .start()
            searchEditText.setText("")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
        }
    }

    /**
     * Shows recently searched apps as a vertical list (with icons, via the
     * search adapter) when the query is empty — icons make a recently used
     * app far quicker to spot than text-only chips did.
     */
    private fun showRecentApps() {
        // The old horizontal chip strip is retired; keep it hidden.
        historyScroll.visibility = View.GONE
        txtNoResults.visibility = View.GONE

        if (!prefs.searchHistoryEnabled) {
            searchAdapter.updateItems(emptyList())
            return
        }
        val historyApps = prefs.getSearchHistory()
            .mapNotNull { id -> allApps.find { it.id == id } }
        if (historyApps.isEmpty()) {
            searchAdapter.updateItems(emptyList())
            return
        }

        val items = mutableListOf<AdapterItem>()
        items.add(AdapterItem(ViewType.HEADER, headerText = getString(R.string.title_recent_searches)))
        for (app in historyApps) {
            items.add(adapterItemForApp(app))
        }
        searchAdapter.updateItems(items)
    }


    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockTickRunnable)
        handler.removeCallbacks(returnToFavoritesRunnable)
        handler.removeCallbacks(pendingAdapterUpdate)
        handler.removeCallbacks(refreshRetry1)
        handler.removeCallbacks(refreshRetry2)
        NotificationRegistry.unregisterListener()
        packageChangeReceiver?.let { runCatching { unregisterReceiver(it) } }
        (packageChangeReceiver as? PackageChangeReceiver)?.destroy()
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        batteryReceiverRegistered = false
    }

    /**
     * Applies background mode at the WINDOW level — not with a hacky overlay View.
     * The window background is drawn behind all content and stays fixed during
     * transitions, unlike a content View which moves with the activity animation.
     */
    private fun applyBackgroundMode() {
        when (prefs.backgroundMode) {
            SlimPreferences.BG_TRANSPARENT -> {
                // Wallpaper fully visible — window background is transparent
                setShowWallpaper(true)
                window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            SlimPreferences.BG_SOLID_BLACK -> {
                // Opaque black at window level — wallpaper is completely covered,
                // stays rock-solid during all transitions and gestures.
                // Drop FLAG_SHOW_WALLPAPER too: otherwise the system keeps
                // compositing the wallpaper in any area the window background
                // doesn't paint — most visibly the status-bar strip once
                // immersive mode hides the bar, leaving a bright empty band.
                setShowWallpaper(false)
                window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                window.statusBarColor = android.graphics.Color.BLACK
                window.navigationBarColor = android.graphics.Color.BLACK
            }
            else -> { // BG_DIMMED
                // Semi-transparent dark tint drawn between wallpaper and content.
                // 18% black — strong enough for readability on bright wallpapers
                // without hiding the wallpaper entirely.
                setShowWallpaper(true)
                window.setBackgroundDrawable(ColorDrawable(0x2E000000))
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
        }
    }

    /** Toggles the window's wallpaper flag so opaque modes don't leak wallpaper. */
    private fun setShowWallpaper(show: Boolean) {
        val hasFlag = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0
        if (hasFlag == show) return  // skip the relayout — addFlags/clearFlags always trigger one
        if (show) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
    }

    /** Hides system status bar and shows launcher-native status info in header. */
    private fun applyImmersiveMode() {
        if (prefs.immersiveMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(android.view.WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
            statusInfoRow.visibility = View.VISIBLE
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
            }
            statusInfoRow.visibility = View.GONE
        }
        updateNotificationSummary()
        updateBatteryLevel()
    }

    /**
     * Updates the header notification chip (immersive mode only). Shows a bell
     * with the active-notification count, and hides entirely at zero so an
     * empty "0" never lingers as visual noise.
     */
    private fun updateNotificationSummary() {
        val count = NotificationRegistry.getNotificationCount()
        if (count > 0) {
            txtNotificationSummary.visibility = View.VISIBLE
            txtNotificationSummary.text = "🔔 $count"
        } else {
            txtNotificationSummary.visibility = View.GONE
        }
    }

    /** Reads current battery level and shows it in the header. */
    private fun updateBatteryLevel() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (scale > 0) (level * 100 / scale) else -1
        txtBattery.text = if (pct >= 0) "🔋 $pct%" else "🔋 --%"
    }

    override fun onNotificationsChanged() {
        // Debounce: music players can fire rapid-fire notification updates; coalesce
        // into a single adapter rebuild per 100ms to avoid cascading frame drops.
        handler.post {
            handler.removeCallbacks(pendingAdapterUpdate)
            handler.postDelayed(pendingAdapterUpdate, 100)
            if (prefs.immersiveMode) updateNotificationSummary()
        }
    }

    // ---- Wave gesture (alphabet index) ----

    /** Keeps the alphabet index compact: only letters that actually have apps. */
    private fun updateAlphabetLetters() {
        if (allApps.isEmpty()) return
        val letters = allApps
            .map { it.displayLabel.firstOrNull()?.uppercaseChar()?.toString() ?: "#" }
            .distinct()
        // The settings gear used to live here, but sitting right after the last
        // letter it was easy to mis-tap on scrub-release. Settings is still
        // reachable via the row at the end of the all-apps list and the Slim
        // entry — so the index stays letters-only.
        waveGestureView.setLetters(letters)
    }

    override fun onLetterSelected(letter: String) {
        // Ignore alphabet scrubbing while the search panel is open — the scrim
        // should block these touches, but guard here too in case any overlay or
        // focus change delivers events out of the expected order.
        if (searchPanel.visibility == View.VISIBLE) return
        if (!isAlphabetScrubbing) {
            isAlphabetScrubbing = true
            updateAdapterData()
        }
        resetInactivityTimer()

        txtLetterPopup.visibility = View.VISIBLE
        txtLetterPopup.text = letter

        val targetIndex = filteredList.indexOfFirst { item ->
            item.type == ViewType.HEADER && item.headerText.equals(letter, ignoreCase = true)
        }
        if (targetIndex != -1) {
            (appRecyclerView.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(targetIndex, 0)
        }
    }

    override fun onLetterReleased() {
        txtLetterPopup.visibility = View.GONE
        resetInactivityTimer()
    }

    // ---- Search filtering & ranking ----

    /**
     * Matches any part of the app name, ranked for relevance:
     * 1. names starting with the query
     * 2. names with a word starting with the query
     * 3. names containing the query anywhere
     */
    private fun filterApps(query: String) {
        if (query.isEmpty()) {
            showRecentApps()
            return
        }
        historyScroll.visibility = View.GONE

        val q = query.lowercase(Locale.getDefault())
        val ranked = allApps.mapNotNull { app ->
            val label = app.displayLabel.lowercase(Locale.getDefault())
            val rank = when {
                label.startsWith(q) -> 0
                label.split(' ', '-', '_', '.').any { it.startsWith(q) } -> 1
                label.contains(q) -> 2
                else -> return@mapNotNull null
            }
            Pair(rank, app)
        }
            .sortedWith(compareBy({ it.first }, { it.second.displayLabel.lowercase(Locale.getDefault()) }))
            .map { it.second }

        val items = ranked.map { adapterItemForApp(it) }
        searchAdapter.updateItems(items)
        txtNoResults.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    // Snapshot notification/media state from the registry into an AdapterItem so
    // DiffUtil can detect per-row content changes without hitting the registry in bind.
    private fun adapterItemForApp(app: AppItem, forceNotif: Boolean = false, favoriteFadeIndex: Int = -1): AdapterItem {
        val media = NotificationRegistry.getMedia(app.packageName)
        return AdapterItem(
            type = ViewType.APP,
            appItem = app,
            forceNotificationPreview = forceNotif,
            mediaTitle = media?.title,
            mediaArtist = media?.artist,
            mediaArt = media?.art,
            notifPreview = if (media == null) NotificationRegistry.getNotificationPreview(app.packageName) else null,
            favoriteFadeIndex = favoriteFadeIndex,
        )
    }

    // Merge Favorites and All Apps into custom RecyclerView elements
    private fun updateAdapterData() {
        val items = mutableListOf<AdapterItem>()

        // 1. Add Favorites section (Limited to top 5 manually selected OR dynamically tracked favorites)
        var favList = favorites.take(5)
        if (favList.isEmpty() && allApps.isNotEmpty()) {
            favList = allApps.sortedByDescending { it.launchCount }.take(5).filter { it.launchCount > 0 }
        }

        if (favList.isNotEmpty()) {
            items.add(AdapterItem(ViewType.HEADER, headerText = getString(R.string.title_favorites)))
            favList.forEachIndexed { index, fav ->
                items.add(adapterItemForApp(fav, favoriteFadeIndex = index))
            }
        }

        // 1b. Active: apps with a live notification that aren't already a favorite.
        // This temporary section surfaces apps like Gmail the moment something
        // arrives and disappears again when the notification is cleared — so they
        // no longer hide until you scrub or search. Home view only; the full
        // alphabetical list below is already exhaustive while scrubbing.
        if (!isAlphabetScrubbing && allApps.isNotEmpty()) {
            val favIds = favList.map { it.id }.toSet()
            val activePackages = NotificationRegistry.getActivePackages()
            val activeApps = allApps.filter {
                it.packageName in activePackages && it.id !in favIds
            }
            if (activeApps.isNotEmpty()) {
                items.add(AdapterItem(ViewType.HEADER, headerText = getString(R.string.title_active)))
                for (app in activeApps) {
                    items.add(adapterItemForApp(app, forceNotif = true))
                }
            }
        }

        // 2. Add All Apps section ONLY when alphabet scrubbing is active
        if (isAlphabetScrubbing && allApps.isNotEmpty()) {
            items.add(AdapterItem(ViewType.HEADER, headerText = getString(R.string.title_all_apps)))
            var currentHeader = ""
            for (app in allApps) {
                val firstChar = app.displayLabel.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                if (firstChar != currentHeader) {
                    currentHeader = firstChar
                    items.add(AdapterItem(ViewType.HEADER, headerText = currentHeader))
                }
                items.add(adapterItemForApp(app))
            }
            // Settings shortcut at the very end of the alphabetical list
            items.add(AdapterItem(ViewType.SETTINGS, headerText = SETTINGS_LETTER))
        }

        filteredList = items
        adapter.updateItems(filteredList)
    }

    private fun showAppOptionsDialog(appItem: AppItem) {
        // Long-pressing Slim's own entry also leads to Settings
        if (appItem.packageName == packageName) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val favoriteOption = if (appItem.isFavorite) {
            getString(R.string.option_remove_favorite)
        } else {
            getString(R.string.option_add_favorite)
        }
        val options = arrayOf(
            favoriteOption,
            getString(R.string.option_rename),
            getString(R.string.option_hide),
            getString(R.string.option_app_info)
        )

        AlertDialog.Builder(this, R.style.Theme_Slim_Dialog)
            .setTitle(appItem.displayLabel)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch {
                        repository.setAppAsFavorite(appItem.id, !appItem.isFavorite)
                    }
                    1 -> showRenameDialog(appItem)
                    2 -> lifecycleScope.launch {
                        repository.setAppHidden(appItem.id, true)
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.app_hidden_toast, appItem.displayLabel),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    3 -> openAppInfo(appItem.packageName)
                }
            }
            .show()
    }

    /** Opens the system App Info screen for force-stop, uninstall, permissions, etc. */
    private fun openAppInfo(packageName: String) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.app_info_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Rename dialog: empty input restores the app's original name. */
    private fun showRenameDialog(appItem: AppItem) {
        val input = EditText(this).apply {
            setText(appItem.displayLabel)
            setSelectAllOnFocus(true)
            hint = appItem.label
        }
        AlertDialog.Builder(this, R.style.Theme_Slim_Dialog)
            .setTitle(getString(R.string.option_rename))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newLabel = input.text.toString().trim()
                lifecycleScope.launch {
                    repository.setCustomLabel(
                        appItem.id,
                        if (newLabel.isEmpty() || newLabel == appItem.label) null else newLabel
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    enum class ViewType { HEADER, APP, SETTINGS }
    data class AdapterItem(
        val type: ViewType,
        val headerText: String = "",
        val appItem: AppItem? = null,
        // Force the notification preview line visible even for non-favorites
        // (used by the home "Active" section).
        val forceNotificationPreview: Boolean = false,
        // Notification/media state snapshotted at list-build time so DiffUtil can
        // detect per-row content changes without re-querying the registry in bind.
        val notifPreview: String? = null,
        val mediaTitle: String? = null,
        val mediaArtist: String? = null,
        val mediaArt: Bitmap? = null,
        // Position within the Favorites section (0-based), used to fade rows
        // progressively toward the bottom of that section; -1 for every other
        // row (full opacity).
        val favoriteFadeIndex: Int = -1,
    )

    class AppListAdapter(
        private val context: Context,
        private var items: List<AdapterItem>,
        private val clickListener: (AppItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val pm: PackageManager = context.packageManager
        private val userManager =
            context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        // Bounded so a device with hundreds of apps doesn't grow this indefinitely —
        // it was a plain HashMap that never evicted.
        private val iconCache = android.util.LruCache<String, Drawable>(ICON_CACHE_SIZE)

        // Adaptive palette (kept readable on any wallpaper)
        private var primaryTextColor = context.getColor(R.color.text_primary)
        private var secondaryTextColor = context.getColor(R.color.text_secondary)
        private var accentColor = context.getColor(R.color.accent_indigo)

        // Text-only mode (Settings > Appearance)
        private var showIcons = true

        fun updateItems(newItems: List<AdapterItem>) {
            val diff = DiffUtil.calculateDiff(AdapterDiffCallback(items, newItems))
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        fun setTextColors(primary: Int, secondary: Int, accent: Int) {
            if (primary == primaryTextColor && secondary == secondaryTextColor && accent == accentColor) return
            primaryTextColor = primary
            secondaryTextColor = secondary
            accentColor = accent
            notifyDataSetChanged()
        }

        fun setShowIcons(show: Boolean) {
            if (show == showIcons) return
            showIcons = show
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return items[position].type.ordinal
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                ViewType.HEADER.ordinal -> {
                    val view = inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                    val textView = view.findViewById<TextView>(android.R.id.text1)
                    textView.textSize = 11f
                    textView.setPadding(16, 28, 16, 6)
                    textView.setAllCaps(true)
                    textView.letterSpacing = 0.06f
                    HeaderViewHolder(view)
                }
                ViewType.SETTINGS.ordinal -> {
                    val view = inflater.inflate(R.layout.item_app, parent, false)
                    SettingsViewHolder(view)
                }
                else -> {
                    val view = inflater.inflate(R.layout.item_app, parent, false)
                    AppViewHolder(view)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderViewHolder) {
                val textView = holder.itemView.findViewById<TextView>(android.R.id.text1)
                textView.text = item.headerText
                textView.setTextColor(accentColor)
            } else if (holder is AppViewHolder && item.appItem != null) {
                val app = item.appItem
                holder.appName.text = app.displayLabel
                holder.appName.setTextColor(primaryTextColor)

                // Favorites fade gently toward the bottom of that section —
                // a quiet decorative touch, not applied outside Favorites.
                holder.itemView.alpha = if (item.favoriteFadeIndex >= 0) {
                    (1f - item.favoriteFadeIndex * FAVORITE_FADE_STEP).coerceAtLeast(FAVORITE_FADE_FLOOR)
                } else {
                    1f
                }

                // Work profile badge
                holder.workBadge.visibility = if (app.isWorkProfile) View.VISIBLE else View.GONE

                // Text-only mode hides icons entirely
                if (!showIcons) {
                    holder.appIcon.visibility = View.GONE
                } else {
                    holder.appIcon.visibility = View.VISIBLE
                    val icon = iconCache.get(app.id) ?: try {
                        var resolvedIcon = pm.getApplicationIcon(app.packageName)
                        if (app.isWorkProfile) {
                            val user = userManager.getUserForSerialNumber(app.userSerial)
                            if (user != null) {
                                resolvedIcon = pm.getUserBadgedIcon(resolvedIcon, user)
                            }
                        }
                        iconCache.put(app.id, resolvedIcon)
                        resolvedIcon
                    } catch (e: Exception) {
                        context.getDrawable(android.R.drawable.sym_def_app_icon)
                    }
                    holder.appIcon.setImageDrawable(icon)
                }

                when {
                    item.mediaTitle != null -> {
                        // Now-playing row: album art (when available) replaces the
                        // app icon, with "Title — Artist" and a ♪ badge.
                        if (showIcons && item.mediaArt != null) {
                            holder.appIcon.visibility = View.VISIBLE
                            holder.appIcon.setImageBitmap(item.mediaArt)
                        }
                        if (app.isFavorite || item.forceNotificationPreview) {
                            holder.notificationPreview.visibility = View.VISIBLE
                            holder.notificationPreview.text =
                                if (!item.mediaArtist.isNullOrEmpty()) "♪ ${item.mediaTitle} — ${item.mediaArtist}"
                                else "♪ ${item.mediaTitle}"
                            holder.notificationPreview.setTextColor(secondaryTextColor)
                        } else {
                            holder.notificationPreview.visibility = View.GONE
                        }
                        holder.notificationCount.visibility = View.VISIBLE
                        holder.notificationCount.text = "♪"
                    }
                    item.notifPreview != null -> {
                        // Show preview text for favorites and the Active section,
                        // badge-only for plain alphabetical rows.
                        if (app.isFavorite || item.forceNotificationPreview) {
                            holder.notificationPreview.visibility = View.VISIBLE
                            holder.notificationPreview.text = item.notifPreview
                            holder.notificationPreview.setTextColor(secondaryTextColor)
                        } else {
                            holder.notificationPreview.visibility = View.GONE
                        }
                        holder.notificationCount.visibility = View.VISIBLE
                        holder.notificationCount.text = "!"
                    }
                    else -> {
                        holder.notificationPreview.visibility = View.GONE
                        holder.notificationCount.visibility = View.GONE
                    }
                }

                holder.itemView.setOnClickListener {
                    clickListener(app, false)
                }
                holder.itemView.setOnLongClickListener {
                    clickListener(app, true)
                    true
                }
            } else if (holder is SettingsViewHolder) {
                // Settings shortcut row
                holder.appIcon.visibility = View.GONE
                holder.appName.text = context.getString(R.string.settings_title)
                holder.appName.setTextColor(accentColor)
                holder.workBadge.visibility = View.GONE
                holder.notificationPreview.visibility = View.GONE
                holder.notificationCount.visibility = View.GONE
                holder.itemView.setOnClickListener {
                    val intent = Intent(context, SettingsActivity::class.java)
                    context.startActivity(intent)
                }
                holder.itemView.setOnLongClickListener(null)
            }
        }

        override fun getItemCount(): Int = items.size

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view)
        class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appIcon: ImageView = view.findViewById(R.id.imgAppIcon)
            val appName: TextView = view.findViewById(R.id.txtAppName)
            val workBadge: TextView = view.findViewById(R.id.txtWorkBadge)
            val notificationPreview: TextView = view.findViewById(R.id.txtNotificationPreview)
            val notificationCount: TextView = view.findViewById(R.id.txtNotificationCount)
        }
        /** Reuses item_app layout but styled as a settings shortcut. */
        class SettingsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val appIcon: ImageView = view.findViewById(R.id.imgAppIcon)
            val appName: TextView = view.findViewById(R.id.txtAppName)
            val workBadge: TextView = view.findViewById(R.id.txtWorkBadge)
            val notificationPreview: TextView = view.findViewById(R.id.txtNotificationPreview)
            val notificationCount: TextView = view.findViewById(R.id.txtNotificationCount)
        }

        private class AdapterDiffCallback(
            private val oldList: List<AdapterItem>,
            private val newList: List<AdapterItem>
        ) : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val o = oldList[oldPos]; val n = newList[newPos]
                if (o.type != n.type) return false
                return when (o.type) {
                    ViewType.APP -> o.appItem?.id == n.appItem?.id
                    ViewType.HEADER -> o.headerText == n.headerText
                    ViewType.SETTINGS -> true
                }
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldList[oldPos] == newList[newPos]
        }

        companion object {
            /** Max resolved-icon Drawables kept in memory at once. */
            private const val ICON_CACHE_SIZE = 250

            /** Alpha lost per row going down the Favorites section. */
            private const val FAVORITE_FADE_STEP = 0.15f

            /** Favorites never fade past this, so even the last one stays legible. */
            private const val FAVORITE_FADE_FLOOR = 0.35f
        }
    }
}
