package io.github.playfoundryhq.slim

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Hosts a single third-party app widget on Slim's home screen.
 *
 * This is the piece a launcher needs to accept widgets from other apps (Duolingo
 * streak, calendar, clock, …): an [AppWidgetHost] that listens for widget
 * updates and renders the bound provider into [container]. Without it the
 * launcher has nowhere to draw a widget and the system never offers Slim in the
 * "add widget" flow.
 *
 * Binding (choosing which widget) is driven from [SettingsActivity] via the
 * system widget picker; this class only renders the already-bound widget id that
 * was persisted in [SlimPreferences.widgetId]. The persistent identity is the
 * (package, [HOST_ID]) pair, so a widget bound by Settings' host renders fine
 * through MainActivity's host as long as both use [HOST_ID].
 */
class WidgetHostManager(
    private val context: Context,
    private val container: FrameLayout,
    private val prefs: SlimPreferences
) {

    private val widgetManager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, HOST_ID)
    private var renderedWidgetId = Int.MIN_VALUE  // sentinel: nothing rendered yet

    /** Begin receiving widget updates. Call from Activity.onStart. */
    fun startListening() {
        try {
            host.startListening()
        } catch (e: Exception) {
            // Some OEM ROMs throw if no widgets are bound yet — safe to ignore.
        }
    }

    /** Stop receiving widget updates. Call from Activity.onStop. */
    fun stopListening() {
        try {
            host.stopListening()
        } catch (e: Exception) {
        }
    }

    /**
     * (Re)draws the currently bound widget into the container, or hides the
     * container when there is none. Self-heals if the widget's app was
     * uninstalled (provider info goes null) by clearing the stale id.
     */
    fun render() {
        val id = prefs.widgetId
        if (id == SlimPreferences.NO_WIDGET) {
            hide()
            return
        }
        // Skip the removeAllViews+createView churn when nothing changed. Recreating
        // AppWidgetHostView on every onResume tears down and re-creates any embedded
        // surfaces the widget holds, causing an InputFlinger focus-token gap that
        // triggers "Application does not have a focused window" ANRs.
        if (id == renderedWidgetId) return
        val info = widgetManager.getAppWidgetInfo(id)
        if (info == null) {
            // Provider vanished (app uninstalled / widget revoked) — forget it.
            prefs.widgetId = SlimPreferences.NO_WIDGET
            hide()
            return
        }

        // Size the slot to the widget's own declared shape so nothing gets
        // stretched: full available width, and a height taken from what the
        // provider says it wants (clamped to a sane band). A 4x1 streak widget
        // stays short; a 2x2 widget gets the vertical room it needs.
        val widthDp = availableWidthDp()
        val heightDp = naturalHeightDp(info)

        // Publish the slot size to the provider BEFORE creating the view.
        // Jetpack Glance widgets (GitHub's contributions, Slack's unreads/status,
        // and a growing share of modern widgets) open a sizing session on bind
        // and, if they never receive a size, close it having produced only an
        // error RemoteViews — the host then shows "Couldn't add widget". The
        // deprecated AppWidgetHostView.updateAppWidgetSize() alone is not enough:
        // Glance 1.1+ reads OPTION_APPWIDGET_SIZES (the List<SizeF>), which that
        // path doesn't reliably populate. So set the options explicitly.
        publishWidgetOptions(id, widthDp, heightDp)

        // Round every widget to the same silhouette regardless of its own corners.
        container.clipToOutline = true
        val lp = container.layoutParams
        lp.height = dpToPx(heightDp)
        container.layoutParams = lp

        container.removeAllViews()
        val hostView: AppWidgetHostView = host.createView(context, id, info)
        hostView.setAppWidget(id, info)

        @Suppress("DEPRECATION")
        hostView.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)

        // The container is now exactly the widget's height, so MATCH_PARENT fills
        // it without distortion.
        container.addView(
            hostView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        container.visibility = View.VISIBLE
        renderedWidgetId = id
    }

    /**
     * Tell the provider the exact box it has, via both the legacy min/max ints
     * and (API 31+) the [AppWidgetManager.OPTION_APPWIDGET_SIZES] list that
     * Glance-based widgets actually read. Safe to call repeatedly; it never
     * touches the host view, so it can't reopen the InputFlinger focus gap that
     * recreating [AppWidgetHostView] would.
     */
    private fun publishWidgetOptions(id: Int, widthDp: Int, heightDp: Int) {
        val options = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    arrayListOf(android.util.SizeF(widthDp.toFloat(), heightDp.toFloat()))
                )
            }
        }
        try {
            widgetManager.updateAppWidgetOptions(id, options)
        } catch (e: Exception) {
            // Provider or id went away between the info lookup and here — render()
            // self-heals on the next pass.
        }
    }

    private fun hide() {
        container.removeAllViews()
        container.visibility = View.GONE
        renderedWidgetId = Int.MIN_VALUE
    }

    /** Width in dp available to the widget: screen minus the alphabet rail + margins. */
    private fun availableWidthDp(): Int {
        val metrics = context.resources.displayMetrics
        val screenDp = metrics.widthPixels / metrics.density
        // ~48dp alphabet rail + ~40dp horizontal margins.
        return (screenDp - 88).toInt().coerceAtLeast(MIN_HEIGHT_DP)
    }

    /**
     * The widget's preferred render height in dp, clamped to a predictable band
     * so a tiny widget isn't lost and a huge one can't dominate the screen.
     * Uses the provider's declared minHeight, preferring the API 31+ target cell
     * span when it implies a taller widget.
     */
    private fun naturalHeightDp(info: AppWidgetProviderInfo): Int {
        val metrics = context.resources.displayMetrics
        var dp = (info.minHeight / metrics.density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellHeight > 0) {
            // Each home-screen cell is roughly CELL_DP tall including spacing.
            dp = maxOf(dp, info.targetCellHeight * CELL_DP)
        }
        val screenHeightDp = (metrics.heightPixels / metrics.density).toInt()
        val maxDp = (screenHeightDp * MAX_HEIGHT_FRACTION).toInt()
        return dp.coerceIn(MIN_HEIGHT_DP, maxDp)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Stable host id identifying Slim as this widget's host. Persistent
         * across process restarts and shared by every [AppWidgetHost] Slim
         * creates so a widget bound in one Activity renders in another.
         */
        const val HOST_ID = 0x5117 // "SLI(m)"

        /** Smallest height a widget slot may shrink to, in dp. */
        private const val MIN_HEIGHT_DP = 64

        /** Largest fraction of screen height a widget slot may occupy. */
        private const val MAX_HEIGHT_FRACTION = 0.45f

        /** Approx dp per home-screen grid cell (incl. spacing) for targetCellHeight. */
        private const val CELL_DP = 74
    }
}
