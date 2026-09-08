package io.github.playfoundryhq.slim

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Helpers for making Slim the system's default Home app.
 *
 * The Home-role assignment is wiped on every (re)install, so Slim needs an
 * easy, robust way to reclaim it — the one-tap RoleManager dialog on Android
 * 10+, falling back to the system Home-settings screen on older versions.
 */
object DefaultLauncherHelper {

    /**
     * True if Slim is currently the system's default Home app.
     *
     * On Android 10+ `RoleManager.isRoleHeld(ROLE_HOME)` is the authoritative
     * signal and is checked first. `resolveActivity(HOME)` is only a heuristic:
     * right after an OEM background-freezer (OxygenOS/ColorOS "Hans") thaws or
     * restarts our process it briefly resolves HOME to the stock launcher or to
     * null even though Slim still holds the role. That false negative used to
     * make [maybePromptDefaultLauncher][/* MainActivity */] pop the role chooser
     * on top of an already-focused home screen, and that spurious chooser launch
     * raced the window-focus handoff into an "Application does not have a focused
     * window" ANR. So we only report "not default" when *both* signals agree.
     */
    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                return true
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Best available intent for becoming the default launcher: the one-tap
     * RoleManager dialog when it's offered and not already held, otherwise the
     * system Home-settings screen.
     */
    fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }
}
