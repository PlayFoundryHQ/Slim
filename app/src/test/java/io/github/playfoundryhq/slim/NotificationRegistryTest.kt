package io.github.playfoundryhq.slim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Per-notification-key bookkeeping behind the inline previews — plain JVM test. */
class NotificationRegistryTest {

    private var changes = 0

    @Before
    fun reset() {
        NotificationRegistry.registerListener(object : NotificationRegistry.NotificationUpdateListener {
            override fun onNotificationsChanged() { changes++ }
        })
        NotificationRegistry.clear()
        changes = 0
    }

    @Test
    fun removingOneOfTwo_keepsTheOtherPreview() {
        NotificationRegistry.updateNotification("chat", "k1", "Alice: hi")
        NotificationRegistry.updateNotification("chat", "k2", "Bob: yo")
        NotificationRegistry.remove("chat", "k2")
        assertEquals("Alice: hi", NotificationRegistry.getNotificationPreview("chat"))
        NotificationRegistry.remove("chat", "k1")
        assertNull(NotificationRegistry.getNotificationPreview("chat"))
        assertEquals(emptySet<String>(), NotificationRegistry.getActivePackages())
    }

    @Test
    fun newestNotificationWins_andAnUpdateCountsAsNewest() {
        NotificationRegistry.updateNotification("chat", "k1", "first")
        NotificationRegistry.updateNotification("chat", "k2", "second")
        assertEquals("second", NotificationRegistry.getNotificationPreview("chat"))
        NotificationRegistry.updateNotification("chat", "k1", "first, edited")
        assertEquals("first, edited", NotificationRegistry.getNotificationPreview("chat"))
    }

    @Test
    fun media_isTrackedByKeyAndCountsAsActive_butNotInBellCount() {
        NotificationRegistry.updateMedia("music", "m1", MediaInfo("Song", "Artist", null))
        NotificationRegistry.updateNotification("chat", "k1", "hi")
        assertEquals(setOf("music", "chat"), NotificationRegistry.getActivePackages())
        assertEquals(1, NotificationRegistry.getNotificationCount())
        assertEquals("Song", NotificationRegistry.getMedia("music")?.title)
        NotificationRegistry.remove("music", "m1")
        assertNull(NotificationRegistry.getMedia("music"))
    }

    @Test
    fun removingUnknownKey_doesNotNotify() {
        NotificationRegistry.updateNotification("chat", "k1", "hi")
        changes = 0
        NotificationRegistry.remove("chat", "nope")
        NotificationRegistry.remove("other", "k1")
        assertEquals(0, changes)
    }

    @Test
    fun clear_dropsEverything() {
        NotificationRegistry.updateNotification("a", "k", "x")
        NotificationRegistry.updateMedia("b", "m", MediaInfo("t", null, null))
        NotificationRegistry.clear()
        assertEquals(emptySet<String>(), NotificationRegistry.getActivePackages())
    }
}
