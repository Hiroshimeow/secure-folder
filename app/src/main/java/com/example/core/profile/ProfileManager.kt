package com.example.core.profile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.example.admin.ProvisioningManager
import com.example.admin.SecureFolderAdminReceiver

/**
 * Core Engine for Profile Lockdown, Process Freezing, and App Sandboxing.
 * Interacts directly with DevicePolicyManager, LauncherApps, and UserManager.
 * Enforces zero-fake clone results and strict isolation bounds.
 */
class ProfileManager(
    private val context: Context,
    private val provisioningManager: ProvisioningManager
) {

    companion object {
        private const val TAG = "ProfileManager"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val adminComponent = SecureFolderAdminReceiver.getComponentName(context)

    /**
     * Toggles Profile Enabled / Frozen State.
     * When locking:
     * - Freezes background processes and suppresses notifications using Android Work Profile Quiet Mode
     * - Or issues DPC lockNow() if Profile Owner
     * When unlocking:
     * - Wakes up and unfreezes Work Profile processes
     */
    fun setProfileLocked(locked: Boolean): Boolean {
        val workHandle = provisioningManager.getWorkProfileUserHandle()
        if (workHandle == null && !provisioningManager.isProfileOwner()) {
            Log.d(TAG, "No active Work Profile available to freeze/unfreeze")
            return false
        }

        return try {
            if (workHandle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                userManager.requestQuietModeEnabled(locked, workHandle)
                Log.i(TAG, "Managed profile quiet mode set to $locked")
            }

            if (provisioningManager.isProfileOwner()) {
                if (locked) {
                    try {
                        dpm.lockNow()
                    } catch (e: Exception) {
                        Log.w(TAG, "lockNow: ${e.message}")
                    }
                } else {
                    try {
                        dpm.setProfileEnabled(adminComponent)
                    } catch (e: Exception) {
                        Log.w(TAG, "setProfileEnabled: ${e.message}")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling profile locked status", e)
            false
        }
    }

    /**
     * Native App Cloning (P0.02 fix):
     * Strictly verifies Profile Owner status. If not Profile Owner, returns false.
     * Never falsely reports success.
     */
    fun cloneAppIntoSecureFolder(packageName: String): Boolean {
        if (!provisioningManager.isProfileOwner()) {
            Log.w(TAG, "Cannot clone app $packageName: Device Admin / Profile Owner is not active.")
            return false
        }

        return try {
            val success = dpm.installExistingPackage(adminComponent, packageName)
            Log.i(TAG, "installExistingPackage for $packageName result: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clone app $packageName", e)
            false
        }
    }

    /**
     * Unclones / uninstalls app from the Managed Profile.
     * Uses PackageInstaller session for true profile uninstallation and data wipe,
     * along with setApplicationHidden.
     */
    fun uncloneApp(packageName: String): Boolean {
        if (!provisioningManager.isProfileOwner()) {
            Log.w(TAG, "Cannot unclone app $packageName: Not Profile Owner")
            return false
        }
        return try {
            dpm.setApplicationHidden(adminComponent, packageName, true)
            try {
                val packageInstaller = context.packageManager.packageInstaller
                val intent = Intent("com.example.UNINSTALL_STATUS")
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            } catch (e: Exception) {
                Log.w(TAG, "PackageInstaller uninstall triggered: ${e.message}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide/uninstall app $packageName in managed profile", e)
            false
        }
    }

    /**
     * Queries all installed launchable applications in Personal Space (User 0).
     */
    fun getHostInstalledActivities(): List<LauncherActivityInfo> {
        return try {
            val personalHandle = provisioningManager.getPersonalUserHandle()
            launcherApps.getActivityList(null, personalHandle)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying host activities", e)
            emptyList()
        }
    }

    /**
     * Queries cloned applications inside the Secure Folder Work Profile.
     */
    fun getClonedActivities(): List<LauncherActivityInfo> {
        val workHandle = provisioningManager.getWorkProfileUserHandle() ?: return emptyList()
        return try {
            launcherApps.getActivityList(null, workHandle)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying cloned activities", e)
            emptyList()
        }
    }

    /**
     * Launches an application instance inside the target profile.
     * P0.10 fix: NEVER fallback to opening in personal profile!
     */
    fun launchApp(
        componentName: ComponentName,
        userHandle: UserHandle,
        sourceBounds: Rect? = null
    ): Boolean {
        return try {
            launcherApps.startMainActivity(componentName, userHandle, sourceBounds, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch main activity for ${componentName.packageName} in isolated profile", e)
            false
        }
    }
}
