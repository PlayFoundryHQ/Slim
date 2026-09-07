package com.opscalehub.slim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Listens for package install/remove/update events and triggers an app-list
 * refresh so newly installed apps appear immediately without waiting for
 * the next onResume cycle.
 */
class PackageChangeReceiver(
    private val onPackagesChanged: suspend () -> Unit
) : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        // Any package add/remove/update triggers a full refresh of the app list
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                scope.launch {
                    onPackagesChanged()
                }
            }
        }
    }

    /** Cancels the receiver's coroutine scope so a pending refresh can't outlive
     *  the Activity that registered it. Call from onDestroy. */
    fun destroy() {
        scope.cancel()
    }

    companion object {
        /** Returns an IntentFilter matching all package-state broadcasts. */
        fun createIntentFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
    }
}
