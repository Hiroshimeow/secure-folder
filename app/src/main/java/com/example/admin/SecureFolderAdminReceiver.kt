package com.example.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Samsung Secure Folder Device Admin Receiver.
 * Controls the Managed Profile lifecycle, lockdown, and cross-profile policies.
 */
class SecureFolderAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "SecureFolderAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, SecureFolderAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Secure Folder Device Admin Enabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Secure Folder Managed Profile Provisioning Complete")

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = getComponentName(context)

        dpm?.let {
            try {
                // Set profile name to match Samsung Secure Folder
                it.setProfileName(admin, "Thư mục Bảo mật")
                // Enable profile immediately
                it.setProfileEnabled(admin)
                Log.i(TAG, "Managed profile enabled successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring managed profile on provisioning complete", e)
            }
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Secure Folder Device Admin Disabled")
    }
}
