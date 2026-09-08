package io.github.playfoundryhq.slim

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/**
 * Hosts Slim's home-screen widget strip: one to [SlimPreferences.MAX_WIDGETS]
 * third-party app widgets, swiped left/right between (see [WidgetPagerView]).
 *
 * This is the piece a launcher needs to accept widgets from other apps: an
 * [AppWidgetHost] that listens for widget updates and renders each bound
 * provider into [container]. Binding (choosing which widget) is driven from
 * [SettingsActivity] via the system widget picker; this class only renders the
 * already-bound ids persisted in [SlimPreferences.widgetIds]. The persistent
 * identity is the (package, [HOST_ID]) pair, so a widget bound by Settings' host
 * renders fine here as long as both use [HOST_ID].
 */
class WidgetHostManager(
    private val context: Context,
    private val container: FrameLayout,
    private val prefs: SlimPreferences
) {

    private val widgetManager = AppWidgetManager.getInstance(context)

    // AppWidgetHostView inflates every widget's RemoteViews with the
    // LayoutInflater of the context this host was built with. An Activity's
    // inflater carries AppCompat's view-substitution factory, which silently
    // rewrites each <ImageView> in the widget to AppCompatImageView — and
    // RemoteViews then rejects setImageResource / setImageViewBitmap on that
    // class ("can't use method with RemoteViews"), so the widget collapses to
    // the "Couldn't add widget" error view. GitHub's contribution widget and
    // Slack's widgets hit this; Duolingo's happens not to. The application
    // context has no such factory and still carries Theme.Slim (declared on
    // <application>), so day/night colours are still correct.
    private val hostContext: Context = context.applicationContext
    private val host = AppWidgetHost(hostContext, HOST_ID)

    // The id list currently on screen. render() rebuilds only when this changes,
    // so an unrelated onResume never recreates an AppWidgetHostView — recreating
    // one tears down the widget's embedded surfaces and opens an InputFlinger
    // focus-token gap that triggers "Application does not have a focused window"
    // ANRs. WidgetPagerView keeps every host view attached for its whole life.
    private var renderedWidgetIds: List<Int> = emptyList()
    private var pager: WidgetPagerView? = null
    private var dotRow: LinearLayout? = null

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

    private var pageHeightsPx = IntArray(0)
    private var heightAnimator: android.animation.ValueAnimator? = null

    /**
     * (Re)draws the widget strip, or hides the container when there are none.
     * One widget fills the view at a time; a swipe snaps to the next
     * ([WidgetPagerView]) and the strip height eases to that widget's own
     * natural height.
     */
    fun render() {
        // Only drop ids the host itself no longer knows (truly deallocated). A
        // plain getAppWidgetInfo() == null is often transient — an OEM freezer
        // briefly hides a just-added provider — and must NOT prune the list.
        val knownToHost = host.appWidgetIds.toHashSet()
        val wanted = prefs.widgetIds
        val pruned = wanted.filter { it in knownToHost }
        if (pruned != wanted) prefs.widgetIds = pruned

        val infos = pruned.mapNotNull { id ->
            widgetManager.getAppWidgetInfo(id)?.let { id to it }
        }
        if (infos.isEmpty()) {
            hide()
            return
        }
        val renderIds = infos.map { it.first }
        if (renderIds == renderedWidgetIds) return

        val widthDp = availableWidthDp()
        val built = ArrayList<Pair<AppWidgetHostView, Int>>(infos.size)
        for ((id, info) in infos) {
            val heightDp = naturalHeightDp(info)
            // Publish the size BEFORE creating the view: Jetpack Glance widgets
            // open a sizing session on bind and, with no size event, close it
            // having emitted only an error RemoteViews. updateAppWidgetSize()
            // alone doesn't reliably populate OPTION_APPWIDGET_SIZES (the
            // List<SizeF> Glance 1.1+ reads), so set the options explicitly.
            publishWidgetOptions(id, widthDp, heightDp)
            val hostView = host.createView(hostContext, id, info)
            hostView.setAppWidget(id, info)
            @Suppress("DEPRECATION")
            hostView.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
            built.add(hostView to heightDp)
        }

        val multi = built.size > 1
        val maxHeightPx = dpToPx(built.maxOf { it.second })
        pageHeightsPx = IntArray(built.size) { dpToPx(built[it].second) }

        heightAnimator?.cancel()
        container.removeAllViews()
        container.clipToOutline = true

        val newPager = WidgetPagerView(context)
        for ((hostView, _) in built) {
            val page = FrameLayout(context)
            page.addView(
                hostView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            newPager.strip.addView(
                page,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        // The pager is always the tallest page tall; the container clips it to
        // the current page's height, which is what "eases" between widgets.
        container.addView(
            newPager,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeightPx)
                .apply { gravity = Gravity.TOP }
        )
        pager = newPager

        if (multi) {
            val dots = buildDots(built.size)
            container.addView(
                dots,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dpToPx(5)
                }
            )
            dotRow = dots
            updateDots(0)
        } else {
            dotRow = null
        }

        newPager.onPageSettled = { page ->
            updateDots(page)
            easeContainerTo(pageHeightsPx.getOrElse(page) { maxHeightPx }, multi)
        }

        setContainerHeight(pageHeightsPx.firstOrNull() ?: maxHeightPx, multi)
        container.visibility = View.VISIBLE
        renderedWidgetIds = renderIds
    }

    private fun chromePx(multi: Boolean) = if (multi) dpToPx(DOTS_STRIP_DP) else 0

    private fun setContainerHeight(pageHeightPx: Int, multi: Boolean) {
        val lp = container.layoutParams
        lp.height = pageHeightPx + chromePx(multi)
        container.layoutParams = lp
    }

    private fun easeContainerTo(pageHeightPx: Int, multi: Boolean) {
        val target = pageHeightPx + chromePx(multi)
        val start = container.height
        if (start == target || start <= 0) {
            setContainerHeight(pageHeightPx, multi)
            return
        }
        heightAnimator?.cancel()
        heightAnimator = android.animation.ValueAnimator.ofInt(start, target).apply {
            duration = 190
            addUpdateListener { a ->
                container.layoutParams = container.layoutParams.apply {
                    height = a.animatedValue as Int
                }
            }
            start()
        }
    }

    /**
     * Tell each provider the exact box it has, via the legacy min/max ints and
     * (API 31+) the [AppWidgetManager.OPTION_APPWIDGET_SIZES] list Glance widgets
     * actually read. Never touches a host view, so it can't reopen the focus gap
     * that recreating [AppWidgetHostView] would.
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

    private fun buildDots(count: Int): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        val size = dpToPx(6)
        val gap = dpToPx(5)
        repeat(count) { i ->
            val dot = View(context)
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            row.addView(
                dot,
                LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = gap }
            )
        }
        return row
    }

    private fun updateDots(activePage: Int) {
        val row = dotRow ?: return
        val on = ContextCompat.getColor(context, R.color.text_primary)
        val off = (ContextCompat.getColor(context, R.color.text_muted) and 0x00FFFFFF) or 0x66000000
        for (i in 0 until row.childCount) {
            (row.getChildAt(i).background as? GradientDrawable)
                ?.setColor(if (i == activePage) on else off)
        }
    }

    private fun hide() {
        heightAnimator?.cancel()
        container.removeAllViews()
        container.visibility = View.GONE
        pager = null
        dotRow = null
        pageHeightsPx = IntArray(0)
        renderedWidgetIds = emptyList()
    }

    /** Width in dp available to a widget: screen minus the alphabet rail + margins. */
    private fun availableWidthDp(): Int {
        val metrics = context.resources.displayMetrics
        val screenDp = metrics.widthPixels / metrics.density
        // ~48dp alphabet rail + ~40dp horizontal margins.
        return (screenDp - 88).toInt().coerceAtLeast(MIN_HEIGHT_DP)
    }

    /**
     * A widget's preferred render height in dp, clamped to a predictable band so
     * a tiny widget isn't lost and a huge one can't dominate the screen. Uses the
     * provider's declared minHeight, preferring the API 31+ target cell span when
     * it implies a taller widget.
     */
    private fun naturalHeightDp(info: AppWidgetProviderInfo): Int {
        val metrics = context.resources.displayMetrics
        var dp = (info.minHeight / metrics.density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellHeight > 0) {
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

        /** Extra vertical room reserved under the strip for the page dots. */
        private const val DOTS_STRIP_DP = 16
    }
}
