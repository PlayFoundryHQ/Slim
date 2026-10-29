package io.github.playfoundryhq.slim

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AppRepository(
    private val context: Context,
    private val appDao: AppDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val allAppsFlow: Flow<List<AppItem>> = appDao.getAllApps()
    val favoritesFlow: Flow<List<AppItem>> = appDao.getFavorites()

    private val launcherApps: LauncherApps
        get() = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    private val userManager: UserManager
        get() = context.getSystemService(Context.USER_SERVICE) as UserManager

    /**
     * Discovers launchable apps across ALL user profiles (personal + work) and
     * syncs the Room cache: new apps are added, renamed apps (an update or a
     * device-language change) get their label refreshed, and uninstalled apps
     * are dropped. Only the difference is written, so a refresh that finds
     * nothing new doesn't make every list observer rebuild.
     *
     * A profile that's paused (work apps turned off) or still locked is skipped
     * entirely — it reports no activities, and treating that as "uninstalled"
     * used to wipe its favorites, hidden flags and renames.
     */
    suspend fun refreshApps() = withContext(ioDispatcher) {
        val myUserHandle = Process.myUserHandle()
        val discoveredApps = mutableListOf<AppItem>()
        val skippedSerials = mutableSetOf<Long>()

        for (profile in launcherApps.profiles) {
            val serial = userManager.getSerialNumberForUser(profile)
            val isWork = profile != myUserHandle
            if (isWork && !isProfileAvailable(profile)) {
                skippedSerials += serial
                continue
            }

            for (activity in launcherApps.getActivityList(null, profile)) {
                val packageName = activity.applicationInfo.packageName
                val className = activity.name
                discoveredApps.add(
                    AppItem(
                        id = AppItem.buildId(packageName, className, serial),
                        packageName = packageName,
                        className = className,
                        label = activity.label.toString(),
                        userSerial = serial,
                        isWorkProfile = isWork
                    )
                )
            }
        }

        // An empty result means LauncherApps had a hiccup, not that every app
        // was uninstalled — never act on it.
        if (discoveredApps.isEmpty()) return@withContext
        val delta = AppSync.computeDelta(appDao.getAllAppsOnce(), discoveredApps, skippedSerials)
        if (!delta.isEmpty) appDao.applyDelta(delta)
    }

    private fun isProfileAvailable(profile: android.os.UserHandle): Boolean {
        return userManager.isUserRunning(profile) &&
            userManager.isUserUnlocked(profile) &&
            !userManager.isQuietModeEnabled(profile)
    }

    suspend fun setAppAsFavorite(id: String, isFavorite: Boolean) = withContext(ioDispatcher) {
        appDao.setFavorite(id, isFavorite)
    }

    suspend fun setAppHidden(id: String, hidden: Boolean) = withContext(ioDispatcher) {
        appDao.setHidden(id, hidden)
    }

    suspend fun setCustomLabel(id: String, label: String?) = withContext(ioDispatcher) {
        appDao.setCustomLabel(id, label)
    }

    suspend fun getHiddenApps(): List<AppItem> = withContext(ioDispatcher) {
        appDao.getHiddenApps()
    }

    suspend fun getCustomizedApps(): List<AppItem> = withContext(ioDispatcher) {
        appDao.getCustomizedApps()
    }

    suspend fun recordAppLaunch(id: String) = withContext(ioDispatcher) {
        appDao.incrementLaunchCount(id, System.currentTimeMillis())
    }

    /**
     * Launches an app in its own user profile (personal or work) via
     * LauncherApps, falling back to a plain intent for the personal profile.
     */
    fun launchApp(appItem: AppItem) {
        val component = ComponentName(appItem.packageName, appItem.className)
        val user = userManager.getUserForSerialNumber(appItem.userSerial) ?: Process.myUserHandle()
        try {
            launcherApps.startMainActivity(component, user, null, null)
        } catch (e: Exception) {
            // Fallback: plain intent launch (personal profile only)
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(appItem.packageName, appItem.className)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                // App could not be launched (uninstalled or restricted)
            }
        }
    }

    /** Resolves the UserHandle for badging work-profile app icons. */
    fun getUserHandleForSerial(serial: Long) =
        userManager.getUserForSerialNumber(serial) ?: Process.myUserHandle()
}

/** Pure diffing between the cached app rows and a fresh LauncherApps scan. */
object AppSync {

    data class Delta(
        val insert: List<AppItem>,
        /** Existing rows whose app-provided label changed (carries the new label). */
        val relabel: List<AppItem>,
        val deleteIds: List<String>,
    ) {
        val isEmpty: Boolean get() = insert.isEmpty() && relabel.isEmpty() && deleteIds.isEmpty()
    }

    /**
     * @param skippedSerials profiles that weren't scanned (paused / locked);
     *   their cached rows are left exactly as they are.
     */
    fun computeDelta(
        cached: List<AppItem>,
        discovered: List<AppItem>,
        skippedSerials: Set<Long> = emptySet(),
    ): Delta {
        val cachedById = cached.associateBy { it.id }
        val discoveredIds = discovered.mapTo(HashSet()) { it.id }
        val insert = discovered.filter { it.id !in cachedById }
        val relabel = discovered.filter { found ->
            val row = cachedById[found.id]
            row != null && row.label != found.label
        }
        val deleteIds = cached
            .filter { it.id !in discoveredIds && it.userSerial !in skippedSerials }
            .map { it.id }
        return Delta(insert, relabel, deleteIds)
    }
}
