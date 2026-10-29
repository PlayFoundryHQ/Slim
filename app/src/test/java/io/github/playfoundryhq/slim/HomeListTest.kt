package io.github.playfoundryhq.slim

import io.github.playfoundryhq.slim.HomeList.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the home list shows in each state — pure logic, plain JVM test. */
class HomeListTest {

    private val titles = HomeList.Titles(favorites = "Favorites", active = "Active", allApps = "All apps")

    private fun app(label: String, pkg: String = "pkg.${label.lowercase()}", launches: Int = 0, fav: Boolean = false) =
        AppItem(
            id = "$pkg/.Main/0", packageName = pkg, className = ".Main", label = label,
            launchCount = launches, isFavorite = fav
        )

    private fun List<Row>.labels() = map {
        when (it) {
            is Row.Header -> "[${it.text}]"
            is Row.App -> it.app.label
            Row.Settings -> "⚙"
        }
    }

    @Test
    fun home_showsFavoritesWithoutHeader_andFadeIndexes() {
        val favs = listOf(app("Mail", fav = true), app("Maps", fav = true))
        val rows = HomeList.build(favs, favs + app("Notes"), emptySet(), scrubbing = false, titles = titles)
        assertEquals(listOf("Mail", "Maps"), rows.labels())
        assertEquals(listOf(0, 1), rows.map { (it as Row.App).favoriteIndex })
    }

    @Test
    fun favorites_cappedAtFive() {
        val favs = (1..7).map { app("Fav$it", fav = true) }
        assertEquals(5, HomeList.favoritesToShow(favs, favs).size)
    }

    @Test
    fun noFavorites_fallsBackToMostLaunched_ignoringNeverLaunched() {
        val apps = listOf(app("A", launches = 2), app("B", launches = 9), app("C", launches = 0))
        assertEquals(listOf("B", "A"), HomeList.favoritesToShow(emptyList(), apps).map { it.label })
    }

    @Test
    fun activeSection_listsNotifyingNonFavorites_onHomeOnly() {
        val mail = app("Mail", fav = true)
        val chat = app("Chat")
        val all = listOf(chat, mail)
        val active = setOf(mail.packageName, chat.packageName)

        val home = HomeList.build(listOf(mail), all, active, scrubbing = false, titles = titles)
        assertEquals(listOf("Mail", "[Active]", "Chat"), home.labels())
        assertTrue((home.last() as Row.App).forceNotificationPreview)

        val scrubbing = HomeList.build(listOf(mail), all, active, scrubbing = true, titles = titles)
        assertTrue("[Active]" !in scrubbing.labels())
    }

    @Test
    fun scrubbing_showsAllAppsByLetter_thenSettings() {
        val mail = app("Mail", fav = true)
        val all = listOf(app("Alarm"), app("atlas"), mail, app("Maps"), app("Zoom"))
        val rows = HomeList.build(listOf(mail), all, emptySet(), scrubbing = true, titles = titles)
        assertEquals(
            listOf(
                "[Favorites]", "Mail",
                "[All apps]", "[A]", "Alarm", "atlas", "[M]", "Mail", "Maps", "[Z]", "Zoom", "⚙"
            ),
            rows.labels()
        )
    }

    @Test
    fun indexLetter_usesRenameAndHandlesEmptyLabel() {
        assertEquals("W", HomeList.indexLetter(app("Mail").copy(customLabel = "work mail")))
        assertEquals("#", HomeList.indexLetter(app("x").copy(label = "")))
    }
}
