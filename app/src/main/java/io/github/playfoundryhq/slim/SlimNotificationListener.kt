package io.github.playfoundryhq.slim

import android.app.Notification
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSession
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class SlimNotificationListener : NotificationListenerService() {

    private val prefs by lazy { SlimPreferences(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val n = sbn.notification

        // 1. Media first — a media notification is also "ongoing", so it must be
        //    routed to the dedicated media row BEFORE the noise filter below, or
        //    the now-playing row would never appear.
        if (isMedia(n)) {
            val info = extractMedia(n)
            if (info != null) {
                NotificationRegistry.updateMedia(packageName, info)
            }
            return
        }

        // 2. Drop ongoing / foreground-service noise (Chrome "tab open", "app
        //    running", VPN, USB, sync, etc.). These are persistent, not alerts.
        val flags = n.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0 ||
            flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        ) {
            return
        }

        // 3. Communication-only filter (user-toggleable). Keep only notifications
        //    a person would care to be interrupted by — messages, calls, email,
        //    social. Apps that fail to tag a category are filtered out in this
        //    mode (known tradeoff). When the toggle is off, everything that
        //    survived the noise filter is shown.
        if (prefs.communicationNotificationsOnly && !isCommunication(n.category)) {
            return
        }

        // 4. Build a readable preview and register it (unchanged behavior).
        val extras = n.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val preview = when {
            title.isNotEmpty() && text.isNotEmpty() -> "$title: $text"
            title.isNotEmpty() -> title
            text.isNotEmpty() -> text
            else -> null
        }
        if (preview != null) {
            NotificationRegistry.updateNotification(packageName, preview)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationRegistry.removeNotification(sbn.packageName)
        NotificationRegistry.removeMedia(sbn.packageName)
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
                        return MediaInfo(title, artist, art)
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
        return MediaInfo(title, artist, art)
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

object NotificationRegistry {
    interface NotificationUpdateListener {
        fun onNotificationsChanged()
    }

    private val activeNotifications = mutableMapOf<String, String>()
    private val activeMedia = mutableMapOf<String, MediaInfo>()
    private var listener: NotificationUpdateListener? = null

    fun registerListener(l: NotificationUpdateListener) {
        listener = l
    }

    fun unregisterListener() {
        listener = null
    }

    fun updateNotification(packageName: String, previewText: String) {
        activeNotifications[packageName] = previewText
        listener?.onNotificationsChanged()
    }

    fun removeNotification(packageName: String) {
        activeNotifications.remove(packageName)
        listener?.onNotificationsChanged()
    }

    fun getNotificationPreview(packageName: String): String? {
        return activeNotifications[packageName]
    }

    // ---- Media (now-playing) ----

    fun updateMedia(packageName: String, info: MediaInfo) {
        activeMedia[packageName] = info
        listener?.onNotificationsChanged()
    }

    fun removeMedia(packageName: String) {
        if (activeMedia.remove(packageName) != null) {
            listener?.onNotificationsChanged()
        }
    }

    fun getMedia(packageName: String): MediaInfo? {
        return activeMedia[packageName]
    }

    /**
     * Packages with at least one active notification OR active media — used to
     * surface them in the home "Active" section.
     */
    fun getActivePackages(): Set<String> {
        return activeNotifications.keys + activeMedia.keys
    }

    /** Header bell count: communication notifications only, media excluded. */
    fun getNotificationCount(): Int {
        return activeNotifications.size
    }
}
