package com.example.core.profile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.example.admin.ProvisioningManager
import com.example.admin.SecureFolderAdminReceiver

/**
 * Profile management engine.
 * Responsibilities:
 * - Quiet mode freezing / unfreezing of the managed profile.
 * - Deduplicated querying of host and managed-profile activities.
 * - Profile-aware launching strictly targeting the managed profile (fail-closed).
 * - Guarding Profile-Owner-only DPM APIs so Personal Controller does not invoke them illegally.
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
     * Toggles quiet mode / freezing of the Work Profile.
     * When locking:
     * - Enables quiet mode on the managed profile to freeze processes and suppress notifications.
     * When unlocking:
     * - Disables quiet mode to resume managed profile processes.
     * Note: Profile-Owner-only lockNow() is invoked ONLY if this instance is actually Profile Owner.
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
                Log.i(TAG, "Managed profile quiet mode requested: locked=$locked")
            }

            // Only call DPM methods if the calling process is legitimately Profile Owner
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
     * Direct silent cloning is deliberately unsupported for this personally-owned Work Profile.
     * DevicePolicyManager.installExistingPackage() requires an affiliated profile, which this
     * product model does not have. Clone/remove must go through ManagedExecutorActivity so Android
     * can mediate user consent and the result can be verified against package-manager truth.
     */
    fun cloneAppIntoSecureFolder(packageName: String): Boolean {
        Log.w(TAG, "Direct clone is unsupported for $packageName; use Managed Executor user-mediated flow")
        return false
    }

    fun uncloneApp(packageName: String): Boolean {
        Log.w(TAG, "Direct remove is unsupported for $packageName; use Managed Executor user-mediated flow")
        return false
    }

    /**
     * Queries all installed launchable activities in Personal Space (User 0).
     * Deduplicates by package name so multiple launcher activities do not cause duplicates.
     */
    fun getHostInstalledActivities(): List<LauncherActivityInfo> {
        return try {
            val personalHandle = provisioningManager.getPersonalUserHandle()
            val rawList = launcherApps.getActivityList(null, personalHandle)
            // Deduplicate by package name
            rawList.distinctBy { it.applicationInfo.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying host activities", e)
            emptyList()
        }
    }

    /**
     * Queries cloned activities inside the Secure Folder Work Profile.
     * Deduplicates by package name so multiple launcher activities from one package produce a single app row.
     */
    fun getClonedActivities(): List<LauncherActivityInfo> {
        val workHandle = provisioningManager.getWorkProfileUserHandle() ?: return emptyList()
        return try {
            val rawList = launcherApps.getActivityList(null, workHandle)
            // Deduplicate by package name
            rawList.distinctBy { it.applicationInfo.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying cloned activities", e)
            emptyList()
        }
    }

    /**
     * Launches an application instance strictly in the managed profile.
     * Fail-closed: Never falls back to personal profile (User 0)!
     */
    fun launchAppInWorkProfile(
        packageName: String,
        componentClassName: String? = null,
        sourceBounds: Rect? = null
    ): Boolean {
        val workHandle = provisioningManager.getWorkProfileUserHandle()
        if (workHandle == null) {
            Log.e(TAG, "Cannot launch app $packageName: Managed Work Profile handle is null.")
            return false
        }

        return try {
            val activities = launcherApps.getActivityList(packageName, workHandle)
            if (activities.isEmpty()) {
                Log.e(TAG, "No launchable activity found for $packageName in managed profile")
                return false
            }

            val targetActivity = if (!componentClassName.isNullOrBlank()) {
                activities.find { it.componentName.className == componentClassName } ?: activities.first()
            } else {
                activities.first()
            }

            launcherApps.startMainActivity(targetActivity.componentName, workHandle, sourceBounds, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName in isolated profile: ${e.message}", e)
            false
        }
    }

    fun launchApp(
        componentName: ComponentName,
        userHandle: UserHandle,
        sourceBounds: Rect? = null
    ): Boolean {
        return try {
            launcherApps.startMainActivity(componentName, userHandle, sourceBounds, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch main activity for ${componentName.packageName}", e)
            false
        }
    }
}
