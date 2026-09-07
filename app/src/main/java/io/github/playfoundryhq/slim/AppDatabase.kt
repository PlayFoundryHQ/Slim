package io.github.playfoundryhq.slim

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM cached_apps WHERE isHidden = 0 ORDER BY label COLLATE NOCASE ASC")
    fun getAllApps(): Flow<List<AppItem>>

    @Query("SELECT * FROM cached_apps WHERE isFavorite = 1 AND isHidden = 0 ORDER BY label COLLATE NOCASE ASC")
    fun getFavorites(): Flow<List<AppItem>>

    @Query("SELECT * FROM cached_apps WHERE isHidden = 1 ORDER BY label COLLATE NOCASE ASC")
    suspend fun getHiddenApps(): List<AppItem>

    /** Apps with user customizations worth backing up. */
    @Query("SELECT * FROM cached_apps WHERE isFavorite = 1 OR isHidden = 1 OR customLabel IS NOT NULL")
    suspend fun getCustomizedApps(): List<AppItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertApps(apps: List<AppItem>)

    @Query("UPDATE cached_apps SET isFavorite = :isFav WHERE id = :id")
    suspend fun setFavorite(id: String, isFav: Boolean)

    @Query("UPDATE cached_apps SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("UPDATE cached_apps SET customLabel = :label WHERE id = :id")
    suspend fun setCustomLabel(id: String, label: String?)

    @Query("UPDATE cached_apps SET launchCount = launchCount + 1, lastTimeUsed = :timestamp WHERE id = :id")
    suspend fun incrementLaunchCount(id: String, timestamp: Long)

    @Query("DELETE FROM cached_apps WHERE packageName = :packageName")
    suspend fun deletePackage(packageName: String)

    /** Removes cache entries for apps that are no longer installed. */
    @Query("DELETE FROM cached_apps WHERE id NOT IN (:validIds)")
    suspend fun deleteStaleApps(validIds: List<String>)

    @Query("SELECT COUNT(*) FROM cached_apps")
    suspend fun getAppCount(): Int
}

@Database(entities = [AppItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2: identity moved from packageName to a composite id
         * (package/class/userSerial) to support work profiles and multiple
         * launcher activities per package. Adds isHidden and customLabel.
         * Favorites and launch counts are preserved.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE cached_apps_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        packageName TEXT NOT NULL,
                        className TEXT NOT NULL,
                        label TEXT NOT NULL,
                        userSerial INTEGER NOT NULL DEFAULT 0,
                        isWorkProfile INTEGER NOT NULL DEFAULT 0,
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        isHidden INTEGER NOT NULL DEFAULT 0,
                        customLabel TEXT,
                        launchCount INTEGER NOT NULL DEFAULT 0,
                        lastTimeUsed INTEGER NOT NULL DEFAULT 0,
                        customTag TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO cached_apps_new
                        (id, packageName, className, label, userSerial, isWorkProfile,
                         isFavorite, isHidden, customLabel, launchCount, lastTimeUsed, customTag)
                    SELECT packageName || '/' || className || '/0', packageName, className, label, 0, 0,
                           isFavorite, 0, NULL, launchCount, lastTimeUsed, customTag
                    FROM cached_apps
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE cached_apps")
                db.execSQL("ALTER TABLE cached_apps_new RENAME TO cached_apps")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "slim_launcher_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
