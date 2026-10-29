package io.github.playfoundryhq.slim

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserManager
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Resolves app icons off the main thread and keeps them in a bounded cache.
 *
 * Resolving an icon is a Binder call into PackageManager plus a resource
 * decode — several ms per app, far more for adaptive icons on a cold process.
 * It used to happen inside onBindViewHolder, so the first alphabet scrub after
 * Slim was killed (routine on OxygenOS/ColorOS) stalled the main thread for
 * every row that scrolled into view. Rows now bind the cached icon if there is
 * one and otherwise get it filled in a moment later.
 *
 * Uses [LauncherApps] so each row shows its own launcher-activity icon (not just
 * the package's) with the work-profile badge already applied.
 */
class IconLoader(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val launcherApps =
        appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager = appContext.getSystemService(Context.USER_SERVICE) as UserManager
    private val cache = LruCache<String, Drawable>(CACHE_SIZE)
    // Limited so a full pre-warm never competes with the UI for every core.
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)
    // ids being resolved right now -> callbacks waiting for them (main thread only).
    private val pending = HashMap<String, MutableList<(Drawable) -> Unit>>()

    /** The cached icon for [app], or null if it hasn't been resolved yet. */
    fun cached(app: AppItem): Drawable? = cache.get(app.id)

    /**
     * Resolves [app]'s icon in the background and hands it to [onLoaded] on the
     * main thread. Concurrent requests for the same app share one lookup.
     * Must be called on the main thread.
     */
    fun load(app: AppItem, onLoaded: (Drawable) -> Unit) {
        cache.get(app.id)?.let { onLoaded(it); return }
        val waiting = pending[app.id]
        if (waiting != null) {
            waiting.add(onLoaded)
            return
        }
        pending[app.id] = mutableListOf(onLoaded)
        scope.launch {
            val icon = withContext(dispatcher) { resolve(app) }
            cache.put(app.id, icon)
            pending.remove(app.id)?.forEach { it(icon) }
        }
    }

    /** Warms the cache for [apps] in order (favorites first, say) so rows bind instantly. */
    fun prewarm(apps: List<AppItem>) {
        val todo = apps.asSequence()
            .filter { cache.get(it.id) == null && it.id !in pending }
            .take(CACHE_SIZE)
            .toList()
        if (todo.isEmpty()) return
        scope.launch {
            for (app in todo) {
                if (cache.get(app.id) != null) continue
                val icon = withContext(dispatcher) { resolve(app) }
                cache.put(app.id, icon)
            }
        }
    }

    /** Drops every cached icon belonging to [packageName] (after an update or removal). */
    fun evictPackage(packageName: String) {
        val prefix = "$packageName/"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    private fun resolve(app: AppItem): Drawable {
        val user = userManager.getUserForSerialNumber(app.userSerial) ?: Process.myUserHandle()
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.className))
        return runCatching { launcherApps.resolveActivity(launchIntent, user)?.getBadgedIcon(0) }
            .getOrNull()
            ?: runCatching { appContext.packageManager.getApplicationIcon(app.packageName) }
                .getOrNull()
            ?: appContext.getDrawable(android.R.drawable.sym_def_app_icon)!!
    }

    companion object {
        /** Max resolved-icon Drawables kept in memory at once. */
        const val CACHE_SIZE = 250
    }
}
