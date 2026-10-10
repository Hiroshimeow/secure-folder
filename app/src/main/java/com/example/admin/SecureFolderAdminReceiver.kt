package com.example.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.core.profile.CrossProfileBridge

/**
 * Enterprise Profile Owner and Device Admin Receiver.
 * Controls the Managed Profile lifecycle, cross-profile intent routing, and command execution.
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

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = getComponentName(context)

        dpm?.let { policyManager ->
            try {
                // Set distinct product-neutral profile name
                policyManager.setProfileName(admin, WORKSPACE_PROFILE_NAME)
                // Enable profile immediately
                policyManager.setProfileEnabled(admin)

                // Configure DPC Cross-Profile Intent Filter so Personal Controller can dispatch commands to Managed Profile
                val filter = IntentFilter().apply {
                    addAction(CrossProfileBridge.ACTION_EXECUTE_COMMAND)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                policyManager.addCrossProfileIntentFilter(
                    admin,
                    filter,
                    DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
                )

                // Allow this package to interact across profiles without restriction (Android 11+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        policyManager.setCrossProfilePackages(admin, setOf(context.packageName))
                    } catch (e: Exception) {
                        Log.w(TAG, "setCrossProfilePackages: ${e.message}")
                    }
                }

                Log.i(TAG, "Private Workspace profile configured and cross-profile intent filter installed")
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring managed profile on provisioning complete", e)
            }
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin Disabled")
    }
}
