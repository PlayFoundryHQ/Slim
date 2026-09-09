package io.github.playfoundryhq.slim

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A minimal snap-pager for the home-screen widget strip: a horizontal row of
 * equal-width pages that snaps to the nearest one when the finger lifts.
 *
 * With [circular] on (more than one widget) it is a **ring** — an over-swipe past
 * the first page rotates around to the last and vice-versa, so there is no start
 * or end and no page indicator is needed.
 *
 * Why not ViewPager2 / RecyclerView: every page holds a live
 * [android.appwidget.AppWidgetHostView], and Slim's hard rule is to never let one
 * be detached and recreated (it tears down the widget's embedded surfaces and
 * opens an InputFlinger focus gap → "no focused window" ANR — see
 * `docs/architecture/system_requirements.md`). This view keeps all host views
 * attached to one [LinearLayout] for their whole lifetime and only changes
 * `scrollX`; nothing is ever recycled.
 */
class WidgetPagerView(context: Context) : HorizontalScrollView(context) {

    /** The horizontal strip holding one full-width page per widget. */
    val strip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
    }

    /** Invoked with the page index whenever scrolling settles on a new page. */
    var onPageSettled: ((Int) -> Unit)? = null

    /** When true, an over-swipe past either end rotates around to the other. */
    var circular = false

    private val pageCount: Int get() = strip.childCount
    private var settledPage = 0
    private var flingHandled = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    // Gesture-start position, captured in onInterceptTouchEvent's ACTION_DOWN
    // (which always fires before onTouchEvent, even when a MOVE is intercepted).
    private var gestureStartX = 0f
    private var interceptY = 0f

    init {
        isHorizontalScrollBarEnabled = false
        // ALWAYS (not NEVER): the edge stretch/glow is the cue that pushing
        // further will rotate the ring around.
        overScrollMode = OVER_SCROLL_ALWAYS
        isFillViewport = true
        addView(strip)
    }

    /**
     * Force every page to exactly the pager's width at measure time. Doing it
     * here (not in onSizeChanged) makes it independent of layout-pass ordering —
     * the pages are `WRAP_CONTENT` until this runs, and a mis-timed stretch left
     * each page sized to its widget's intrinsic width, so nothing snapped.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        if (w <= 0) return
        var changed = false
        for (i in 0 until strip.childCount) {
            val child = strip.getChildAt(i)
            if (child.layoutParams.width != w) {
                child.layoutParams =
                    LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
                changed = true
            }
        }
        if (changed) {
            strip.measure(
                MeasureSpec.makeMeasureSpec(w * pageCount, MeasureSpec.EXACTLY),
                heightMeasureSpec
            )
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0) post { scrollTo(settledPage * w, 0) }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val page = currentPage()
        if (page != settledPage) {
            settledPage = page
            onPageSettled?.invoke(page)
        }
    }

    /**
     * Claim every horizontal drag ourselves — including an over-drag at an edge,
     * which the base class ignores because there's nothing left to scroll. Without
     * this the edge over-swipe that rotates the ring would fall through to the
     * widget and never reach [onTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = ev.x
                interceptY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.x - gestureStartX)
                val dy = abs(ev.y - interceptY)
                if (pageCount > 1 && dx > touchSlop && dx > dy * 1.2f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    // A scroll container, not a click target — the pages (widgets) handle their
    // own taps. Snapping on finger-up is scroll behaviour, not a click.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureStartX = ev.x
            flingHandled = false
        }
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            if (!flingHandled) settle(ev.x - gestureStartX)
        }
        return handled
    }

    /**
     * Base fling would coast between pages; keep it to one step and edge-aware.
     * The base class passes `-fingerVelocity` here (content scrolls opposite the
     * finger), so a rightward flick arrives negative — convert back to the
     * finger-travel sign [settle] expects (right = positive).
     */
    override fun fling(velocityX: Int) {
        flingHandled = true
        settle(if (velocityX < -900) FLING_DX else if (velocityX > 900) -FLING_DX else 0f)
    }

    /**
     * @param dx horizontal finger travel (or a synthetic fling nudge): positive =
     *   swiped right = wants the previous page.
     */
    private fun settle(dx: Float) {
        if (width == 0 || pageCount == 0) return
        val maxScroll = (pageCount - 1) * width
        val atStart = scrollX <= width / 12
        val atEnd = scrollX >= maxScroll - width / 12
        val wrap = width * 0.22f
        when {
            circular && pageCount > 1 && atStart && dx > wrap -> smoothScrollTo(maxScroll, 0)
            circular && pageCount > 1 && atEnd && dx < -wrap -> smoothScrollTo(0, 0)
            else -> smoothScrollTo(currentPage() * width, 0)
        }
    }

    fun snapToNearest() {
        if (pageCount > 1 && width != 0) smoothScrollTo(currentPage() * width, 0)
    }

    private fun currentPage(): Int {
        if (width == 0) return 0
        return (scrollX.toFloat() / width).roundToInt()
            .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    private companion object {
        /** Synthetic finger-travel a qualifying fling stands in for. */
        const val FLING_DX = 10_000f
    }
}
