package io.github.playfoundryhq.slim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Search panel relevance ranking — pure logic, plain JVM test. */
class SearchRankerTest {

    private fun app(label: String, custom: String? = null) =
        AppItem(id = "$label/.Main/0", packageName = "pkg.$label", className = ".Main", label = label, customLabel = custom)

    private fun rank(query: String, vararg labels: String) =
        SearchRanker.rank(labels.map { app(it) }, query, Locale.ROOT).map { it.label }

    @Test
    fun prefixBeatsWordStartBeatsSubstring() {
        assertEquals(
            listOf("Maps", "Google Maps", "Bitmaps"),
            rank("map", "Bitmaps", "Google Maps", "Maps", "Calendar")
        )
    }

    @Test
    fun wordStart_splitsOnDashUnderscoreAndDot() {
        // All rank as word-start matches, then sort by label ('-' < '.' < '_').
        assertEquals(listOf("my-notes", "my.notes", "my_notes"), rank("no", "my_notes", "my.notes", "my-notes"))
    }

    @Test
    fun caseInsensitive_andAlphabeticalWithinRank() {
        assertEquals(listOf("camera", "Clock", "CLOCK 2"), rank("C", "CLOCK 2", "Clock", "camera"))
    }

    @Test
    fun matchesRenamedLabel() {
        val renamed = app("Gmail", custom = "Work mail")
        assertEquals(listOf(renamed), SearchRanker.rank(listOf(renamed), "work", Locale.ROOT))
    }

    @Test
    fun noMatchOrEmptyQuery_isEmpty() {
        assertTrue(rank("xyz", "Maps").isEmpty())
        assertTrue(rank("", "Maps").isEmpty())
    }
}
