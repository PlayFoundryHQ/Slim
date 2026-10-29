package io.github.playfoundryhq.slim

import java.util.Locale

/**
 * Decides what the home list shows — pure logic, no Android types, so it can
 * be unit-tested. [MainActivity] turns the rows into adapter items (adding the
 * live notification/media snapshot for each app).
 */
object HomeList {

    sealed interface Row {
        data class Header(val text: String) : Row
        data class App(
            val app: AppItem,
            /** Show the notification preview even though it's not a favorite (Active section). */
            val forceNotificationPreview: Boolean = false,
            /** Position within Favorites (0-based) for the fade effect; -1 elsewhere. */
            val favoriteIndex: Int = -1,
        ) : Row
        /** The Slim Settings shortcut at the very end of the alphabetical list. */
        data object Settings : Row
    }

    data class Titles(val favorites: String, val active: String, val allApps: String)

    /** At most this many favorites are shown on the home view. */
    const val MAX_FAVORITES = 5

    /**
     * The user's favorites (first [MAX_FAVORITES]); with none chosen, falls back
     * to the most-launched apps so a fresh install's home isn't empty for long.
     */
    fun favoritesToShow(favorites: List<AppItem>, allApps: List<AppItem>): List<AppItem> {
        val chosen = favorites.take(MAX_FAVORITES)
        if (chosen.isNotEmpty()) return chosen
        return allApps.sortedByDescending { it.launchCount }
            .take(MAX_FAVORITES)
            .filter { it.launchCount > 0 }
    }

    /**
     * @param allApps visible apps, already sorted by display label.
     * @param activePackages packages with a live notification or media session.
     * @param scrubbing true while the alphabet index is in use (full list shown).
     */
    fun build(
        favorites: List<AppItem>,
        allApps: List<AppItem>,
        activePackages: Set<String>,
        scrubbing: Boolean,
        titles: Titles,
    ): List<Row> {
        val rows = mutableListOf<Row>()

        // 1. Favorites. On the home view the list *is* your favorites — the
        // header is just noise; keep it only while scrubbing, where it separates
        // them from the "All Apps" section below.
        val favList = favoritesToShow(favorites, allApps)
        if (favList.isNotEmpty()) {
            if (scrubbing) rows += Row.Header(titles.favorites)
            favList.forEachIndexed { index, fav -> rows += Row.App(fav, favoriteIndex = index) }
        }

        // 2. Active: apps with a live notification that aren't already a
        // favorite. Surfaces apps like Gmail the moment something arrives and
        // disappears again when it's cleared. Home view only — the full list
        // below is already exhaustive while scrubbing.
        if (!scrubbing) {
            val favIds = favList.mapTo(HashSet()) { it.id }
            val activeApps = allApps.filter { it.packageName in activePackages && it.id !in favIds }
            if (activeApps.isNotEmpty()) {
                rows += Row.Header(titles.active)
                activeApps.forEach { rows += Row.App(it, forceNotificationPreview = true) }
            }
        }

        // 3. All apps, grouped by first letter — only while scrubbing.
        if (scrubbing && allApps.isNotEmpty()) {
            rows += Row.Header(titles.allApps)
            var currentHeader = ""
            for (app in allApps) {
                val letter = indexLetter(app)
                if (letter != currentHeader) {
                    currentHeader = letter
                    rows += Row.Header(letter)
                }
                rows += Row.App(app)
            }
            rows += Row.Settings
        }
        return rows
    }

    /** The alphabet-index letter an app files under ("#" for an empty label). */
    fun indexLetter(app: AppItem): String =
        app.displayLabel.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
}

/** Relevance ranking for the search panel. */
object SearchRanker {

    /**
     * Apps whose name contains [query] (case-insensitive), best first:
     * 1. the name starts with the query,
     * 2. a word in the name starts with it (split on space, '-', '_', '.'),
     * 3. it appears anywhere;
     * alphabetical within each rank.
     */
    fun rank(apps: List<AppItem>, query: String, locale: Locale = Locale.getDefault()): List<AppItem> {
        if (query.isEmpty()) return emptyList()
        val q = query.lowercase(locale)
        return apps.mapNotNull { app ->
            val label = app.displayLabel.lowercase(locale)
            val rank = when {
                label.startsWith(q) -> 0
                label.split(' ', '-', '_', '.').any { it.startsWith(q) } -> 1
                label.contains(q) -> 2
                else -> return@mapNotNull null
            }
            rank to app
        }
            .sortedWith(compareBy({ it.first }, { it.second.displayLabel.lowercase(locale) }))
            .map { it.second }
    }
}
