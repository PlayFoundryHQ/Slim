package io.github.playfoundryhq.slim

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_apps")
data class AppItem(
    /**
     * Stable identity: "packageName/className/userSerial".
     * The same package can exist in both the personal and work profile,
     * and a package can expose several launcher activities.
     */
    @PrimaryKey val id: String,
    val packageName: String,
    val className: String,
    val label: String,
    /** Serial number of the Android user profile this app belongs to. */
    val userSerial: Long = 0L,
    val isWorkProfile: Boolean = false,
    val isFavorite: Boolean = false,
    /** Hidden apps are excluded from the list and search (managed in Settings). */
    val isHidden: Boolean = false,
    /** User-chosen display name; null means use the original label. */
    val customLabel: String? = null,
    val launchCount: Int = 0,
    val lastTimeUsed: Long = 0L,
    val customTag: String? = null
) {
    /** The name shown in lists and used for search/sorting. */
    val displayLabel: String
        get() = customLabel ?: label

    companion object {
        fun buildId(packageName: String, className: String, userSerial: Long): String =
            "$packageName/$className/$userSerial"
    }
}
