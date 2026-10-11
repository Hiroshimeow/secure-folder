package com.example.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-applies idempotent managed-profile policy after APK replacement.
 * MY_PACKAGE_REPLACED is delivered separately to each installed user/profile instance.
 */
class WorkspaceMigrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val configured = ProvisioningManager(context.applicationContext).ensureManagedProfileConfiguration()
        if (configured) {
            Log.i("WorkspaceMigration", "Managed-profile policy migrated after package replacement")
        }
    }
}
