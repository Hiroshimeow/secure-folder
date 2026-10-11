package com.example.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Enterprise Profile Owner and Device Admin Receiver.
 * Policy setup is delegated to ProvisioningManager so the same idempotent migration path
 * runs both after fresh provisioning and after app upgrades.
 */
class SecureFolderAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "SecureFolderAdmin"
        const val WORKSPACE_PROFILE_NAME = "Private Workspace"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, SecureFolderAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin Enabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Managed Profile Provisioning Complete")

        val configured = ProvisioningManager(context.applicationContext).ensureManagedProfileConfiguration()
        if (!configured) {
            Log.e(TAG, "Managed profile provisioning completed but policy configuration failed")
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin Disabled")
    }
}
