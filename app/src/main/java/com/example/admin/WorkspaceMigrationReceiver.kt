package com.example.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.core.profile.WorkspaceAccessController

/**
 * Re-applies DPC policy and the fail-closed app gate after upgrades/reboots.
 *
 * Work Profile stays running; this receiver only ensures every protected package starts hidden +
 * suspended until the Personal Controller establishes a verified PRIMARY session.
 */
class WorkspaceMigrationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WorkspaceMigration"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        val appContext = context.applicationContext
        val provisioning = ProvisioningManager(appContext)

        // Personal profile has no DPC authority; only the managed-profile copy owns this boundary.
        if (!provisioning.isProfileOwner()) return

        val configured = provisioning.ensureManagedProfileConfiguration()
        val gateResult = WorkspaceAccessController(appContext).enforceFailClosedOnProcessStart()

        Log.i(
            TAG,
            "Fail-closed workspace recovery action=$action configured=$configured " +
                "gateSuccess=${gateResult.success} failures=${gateResult.failedPackages.size}"
        )
    }
}
