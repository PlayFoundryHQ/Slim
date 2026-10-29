package io.github.playfoundryhq.slim

import android.app.Notification
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

class SlimNotificationListener : NotificationListenerService() {

    private val prefs by lazy { SlimPreferences(this) }

    // Listener callbacks arrive on the main thread — the same one drawing the
    // home screen. Reading a MediaSession (Binder) and decoding album art there
    // stalled frames every time a player updated its notification, so media
    // extraction runs on this single background thread instead.
    private val mediaExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    // Latest media request per notification key (main thread only), stamped
    // from a monotonic counter. A background result is dropped unless it is
    // still the latest for its key — so a slow extraction can't resurrect a
    // row that was removed (entry gone) or overwrite a newer one.
    private val mediaGeneration = HashMap<String, Long>()
    private var mediaRequestCounter = 0L

    override fun onDestroy() {
        mediaExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * (Re)connected — after Slim's process was killed and restarted, after the
     * user granted access, or after a rebind. Nothing posted while we were gone
     * was delivered, so rebuild the registry from what's in the shade right now;
     * otherwise previews and the "Active" section stay empty until the next
     * notification arrives.
     */
    override fun onListenerConnected() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        NotificationRegistry.clear()
        mediaGeneration.clear()
        active.forEach { handlePosted(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handlePosted(sbn)

    private fun handlePosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val key = sbn.key
        val n = sbn.notification

        // 1. Media first — a media notification is also "ongoing", so it must be
        //    routed to the dedicated media row BEFORE the noise filter below, or
        //    the now-playing row would never appear.
        if (isMedia(n)) {
            val generation = ++mediaRequestCounter
            mediaGeneration[key] = generation
            mediaExecutor.execute {
                val info = runCatching { extractMedia(n) }.getOrNull() ?: return@execute
                mainHandler.post {
                    if (mediaGeneration[key] == generation) {
                        NotificationRegistry.updateMedia(packageName, key, info)
                    }
                }
            }
            return
        }

        // An update can turn a shown notification into one we filter out
        // (e.g. it became ongoing) — drop the old entry in that case.
        val preview = previewFor(n)
        if (preview == null) {
            NotificationRegistry.remove(packageName, key)
        } else {
            NotificationRegistry.updateNotification(packageName, key, preview)
        }
    }

    /** The preview line for [n], or null when it's noise or filtered out. */
    private fun previewFor(n: Notification): String? {
        // 2. Drop ongoing / foreground-service noise (Chrome "tab open", "app
        //    running", VPN, USB, sync, etc.). These are persistent, not alerts.
        //    Group summaries ("3 new messages") only repeat their children.
        val flags = n.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0 ||
            flags and Notification.FLAG_FOREGROUND_SERVICE != 0 ||
            flags and Notification.FLAG_GROUP_SUMMARY != 0
        ) {
            return null
        }

        // 3. Communication-only filter (user-toggleable). Keep only notifications
        //    a person would care to be interrupted by — messages, calls, email,
        //    social. Apps that fail to tag a category are filtered out in this
        //    mode (known tradeoff). When the toggle is off, everything that
        //    survived the noise filter is shown.
        if (prefs.communicationNotificationsOnly && !isCommunication(n.category)) {
            return null
        }

        // 4. Build a readable preview.
        val extras = n.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        return when {
            title.isNotEmpty() && text.isNotEmpty() -> "$title: $text"
            title.isNotEmpty() -> title
            text.isNotEmpty() -> text
            else -> null
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        mediaGeneration.remove(sbn.key)
        NotificationRegistry.remove(sbn.packageName, sbn.key)
    }

    private fun isMedia(n: Notification): Boolean {
        if (n.category == Notification.CATEGORY_TRANSPORT) return true
        return mediaToken(n) != null
    }

    @Suppress("DEPRECATION")
    private fun mediaToken(n: Notification): MediaSession.Token? =
        n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)

    /**
     * Pulls title / artist / album-art for the now-playing row. Prefers the
     * MediaSession metadata (richest, kept in sync by the player); falls back to
     * the notification's own title/text + large icon. Building a [MediaController]
     * from the session token works under the existing notification-listener
     * grant — no MEDIA_CONTENT_CONTROL permission needed.
     */
    private fun extractMedia(n: Notification): MediaInfo? {
        val token = mediaToken(n)
        if (token != null) {
            try {
                val controller = MediaController(this, token)
                val md = controller.metadata
                if (md != null) {
                    val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                        ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                        ?: md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    val art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
                    if (!title.isNullOrEmpty()) {
                        return MediaInfo(title, artist, art?.let { downscale(it) })
                    }
                }
            } catch (_: Exception) {
                // fall through to notification extras
            }
        }
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrEmpty()) return null
        val art = try {
            n.getLargeIcon()?.loadDrawable(this)?.let { drawableToBitmap(it) }
        } catch (_: Exception) {
            null
        }
        return MediaInfo(title, artist, art?.let { downscale(it) })
    }

    /**
     * Album art arrives at full resolution (often 1000px+, megabytes each) but is
     * drawn in a 34dp icon slot; keep a copy at twice that size instead.
     */
    private fun downscale(bitmap: Bitmap): Bitmap {
        val maxPx = (ART_SIZE_DP * resources.displayMetrics.density).toInt()
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxPx) return bitmap
        val scale = maxPx.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap? {
        if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }

    private fun isCommunication(category: String?): Boolean = category in COMMUNICATION_CATEGORIES

    companion object {
        /** Album art is kept at this size (2× the 34dp icon slot). */
        private const val ART_SIZE_DP = 68

        // "missed_call" is referenced as a literal so the listener still compiles
        // on minSdk 26 (Notification.CATEGORY_MISSED_CALL is API 33+).
        private val COMMUNICATION_CATEGORIES = setOf(
            Notification.CATEGORY_MESSAGE,
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_EMAIL,
            Notification.CATEGORY_SOCIAL,
            "missed_call"
        )
    }
}

/** Now-playing metadata for the media row. */
data class MediaInfo(
    val title: String,
    val artist: String?,
    val art: Bitmap?
)

/**
 * In-memory notification state for the home list, owned by the main thread.
 *
 * Tracked per notification **key**, grouped by package: an app with several
 * notifications keeps its preview until the last one is gone (a single
 * removal used to clear the whole package), and the newest one is shown.
 */
object NotificationRegistry {
    interface NotificationUpdateListener {
        fun onNotificationsChanged()
    }

    // package -> (notification key -> preview), in arrival order (newest last).
    private val activeNotifications = mutableMapOf<String, LinkedHashMap<String, String>>()
    private val activeMedia = mutableMapOf<String, LinkedHashMap<String, MediaInfo>>()
    private var listener: NotificationUpdateListener? = null

    fun registerListener(l: NotificationUpdateListener) {
        listener = l
    }

    fun unregisterListener() {
        listener = null
    }

    fun updateNotification(packageName: String, key: String, previewText: String) {
        activeNotifications.getOrPut(packageName) { LinkedHashMap() }.apply {
            remove(key) // re-insert so an updated notification counts as newest
            put(key, previewText)
        }
        listener?.onNotificationsChanged()
    }

    /** Removes one notification (text or media) by key. */
    fun remove(packageName: String, key: String) {
        val removedText = removeKey(activeNotifications, packageName, key)
        val removedMedia = removeKey(activeMedia, packageName, key)
        if (removedText || removedMedia) listener?.onNotificationsChanged()
    }

    /** The newest preview for [packageName], or null if it has none. */
    fun getNotificationPreview(packageName: String): String? =
        activeNotifications[packageName]?.values?.lastOrNull()

    // ---- Media (now-playing) ----

    fun updateMedia(packageName: String, key: String, info: MediaInfo) {
        activeMedia.getOrPut(packageName) { LinkedHashMap() }.apply {
            remove(key)
            put(key, info)
        }
        listener?.onNotificationsChanged()
    }

    fun getMedia(packageName: String): MediaInfo? = activeMedia[packageName]?.values?.lastOrNull()

    /** Forgets everything — used before re-seeding from the system's active list. */
    fun clear() {
        if (activeNotifications.isEmpty() && activeMedia.isEmpty()) return
        activeNotifications.clear()
        activeMedia.clear()
        listener?.onNotificationsChanged()
    }

    /**
     * Packages with at least one active notification OR active media — used to
     * surface them in the home "Active" section.
     */
    fun getActivePackages(): Set<String> {
        return activeNotifications.keys + activeMedia.keys
    }

    /** Header bell count: apps with a (non-media) notification. */
    fun getNotificationCount(): Int {
        return activeNotifications.size
    }

    private fun <V> removeKey(map: MutableMap<String, LinkedHashMap<String, V>>, pkg: String, key: String): Boolean {
        val entries = map[pkg] ?: return false
        if (entries.remove(key) == null) return false
        if (entries.isEmpty()) map.remove(pkg)
        return true
    }
}
