package io.github.playfoundryhq.slim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cache-vs-scan diffing used by AppRepository.refreshApps — plain JVM test. */
class AppSyncTest {

    private fun app(pkg: String, label: String = pkg, serial: Long = 0L, fav: Boolean = false) =
        AppItem(
            id = AppItem.buildId(pkg, ".Main", serial), packageName = pkg, className = ".Main",
            label = label, userSerial = serial, isWorkProfile = serial != 0L, isFavorite = fav
        )

    @Test
    fun unchangedScan_isEmptyDelta() {
        val cached = listOf(app("a"), app("b"))
        assertTrue(AppSync.computeDelta(cached, listOf(app("a"), app("b"))).isEmpty)
    }

    @Test
    fun newApp_isInserted_removedApp_isDeleted() {
        val delta = AppSync.computeDelta(listOf(app("a"), app("gone")), listOf(app("a"), app("new")))
        assertEquals(listOf("new"), delta.insert.map { it.packageName })
        assertEquals(listOf(app("gone").id), delta.deleteIds)
        assertTrue(delta.relabel.isEmpty())
    }

    @Test
    fun changedLabel_isRelabelled_notReinserted() {
        val delta = AppSync.computeDelta(
            cached = listOf(app("a", label = "Old name", fav = true)),
            discovered = listOf(app("a", label = "New name"))
        )
        assertEquals(listOf("New name"), delta.relabel.map { it.label })
        assertTrue(delta.insert.isEmpty())
        assertTrue(delta.deleteIds.isEmpty())
    }

    @Test
    fun skippedProfile_keepsItsRows() {
        val work = app("w", serial = 10L, fav = true)
        val delta = AppSync.computeDelta(
            cached = listOf(app("a"), work),
            discovered = listOf(app("a")),
            skippedSerials = setOf(10L)
        )
        assertTrue(delta.isEmpty)
    }

    @Test
    fun sameAppInTwoProfiles_isTrackedSeparately() {
        val delta = AppSync.computeDelta(
            cached = listOf(app("a"), app("a", serial = 10L)),
            discovered = listOf(app("a"))
        )
        assertEquals(listOf(app("a", serial = 10L).id), delta.deleteIds)
    }
}
