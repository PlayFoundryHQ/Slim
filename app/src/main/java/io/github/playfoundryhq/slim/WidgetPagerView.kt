package io.github.playfoundryhq.slim

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * A minimal snap-pager for the home-screen widget strip: a horizontal row of
 * equal-width pages that snaps to the nearest one when the finger lifts, and
 * reports the settled page so [WidgetHostManager] can update its dots.
 *
 * Why not ViewPager2 / RecyclerView: every page holds a live
 * [android.appwidget.AppWidgetHostView], and Slim's hard rule is to never let
 * one be detached and recreated (it tears down the widget's embedded surfaces
 * and opens an InputFlinger focus gap → "no focused window" ANR — see
 * `docs/architecture/system_requirements.md`). This view keeps all host views
 * attached to one [LinearLayout] for their whole lifetime and only changes
 * `scrollX`; nothing is ever recycled.
 */
class WidgetPagerView(context: Context) : HorizontalScrollView(context) {

    /** The horizontal strip holding one full-width page per widget. */
    val strip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT
        )
    }

    /** Invoked with the page index whenever scrolling settles on a new page. */
    var onPageSettled: ((Int) -> Unit)? = null

    private val pageCount: Int get() = strip.childCount
    private var settledPage = 0

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
        addView(strip)
    }

    /** Re-stretch every page to the pager's own width when that changes. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || w == oldw) return
        for (i in 0 until strip.childCount) {
            strip.getChildAt(i).layoutParams =
                LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        strip.requestLayout()
        // Keep the current page aligned after a width change (e.g. rotation).
        post { scrollTo(settledPage * w, 0) }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val page = currentPage()
        if (page != settledPage) {
            settledPage = page
            onPageSettled?.invoke(page)
        }
    }

    // A scroll container, not a click target — the pages (widgets) handle their
    // own taps. Snapping on finger-up is scroll behaviour, not a click.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            snapToNearest()
        }
        return handled
    }

    /** A fling still ends in a clean snap rather than resting between pages. */
    override fun fling(velocityX: Int) {
        if (pageCount <= 1 || width == 0) return
        val target = when {
            velocityX > 800 -> settledPage + 1
            velocityX < -800 -> settledPage - 1
            else -> currentPage()
        }.coerceIn(0, pageCount - 1)
        smoothScrollTo(target * width, 0)
    }

    fun snapToNearest() {
        if (pageCount <= 1 || width == 0) return
        smoothScrollTo(currentPage() * width, 0)
    }

    private fun currentPage(): Int {
        if (width == 0) return 0
        return (scrollX.toFloat() / width).roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }
}
