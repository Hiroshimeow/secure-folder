package com.example.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log

enum class ProfileEnvironment {
    MAIN_PROFILE,
    MANAGED_PROFILE
}

enum class ProvisioningState {
    NOT_PROVISIONED,
    PROVISIONING_ALLOWED,
    ALREADY_PROVISIONED,
    DEVICE_NOT_SUPPORTED
}

/**
 * Enterprise Work Profile and Device Policy Manager orchestrator.
 * Crucial Architecture:
 * - User 0 is the Personal Profile (isProfileOwner is FALSE).
 * - User 11 is the Managed Work Profile (isProfileOwner is TRUE).
 * - System is considered Active if either current user is Profile Owner OR a Managed Work Profile
 *   handle exists and belongs to this DPC container.
 */
class ProvisioningManager(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val adminComponent: ComponentName = SecureFolderAdminReceiver.getComponentName(context)

    fun getEnvironment(): ProfileEnvironment {
        return if (userManager.isManagedProfile) {
            ProfileEnvironment.MANAGED_PROFILE
        } else {
            ProfileEnvironment.MAIN_PROFILE
        }
    }

    /**
     * Checks if current calling user is Profile Owner.
     */
    fun isProfileOwner(): Boolean {
        return try {
            dpm.isProfileOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if the overall Work Profile architecture is active and provisioned.
     * True if current user is Profile Owner OR a valid Work Profile user handle exists!
     */
    fun isWorkProfileActive(): Boolean {
        return isProfileOwner() || getWorkProfileUserHandle() != null
    }

    fun isAdminActive(): Boolean {
        return try {
            dpm.isAdminActive(adminComponent)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Precisely locates the Work Profile user handle.
     * Accurately filters out:
     * - Current user handle (User 0)
     * - Samsung Dual Messenger (User 95)
     * - Samsung Knox Secure Folder native container (User 150)
     * And validates Managed Profile status via LauncherApps & UserManager.
     */
    fun getWorkProfileUserHandle(): UserHandle? {
        val myHandle = Process.myUserHandle()
        if (userManager.isManagedProfile) {
            return myHandle
        }

        try {
            val candidateProfiles = launcherApps.profiles
            for (handle in candidateProfiles) {
                if (handle == myHandle) continue

                val handleStr = handle.toString()
                // Explicitly filter out Samsung Dual Messenger (User 95) and Secure Folder OEM (User 150)
                if (handleStr.contains("95") || handle.hashCode() == 95 ||
                    handleStr.contains("150") || handle.hashCode() == 150) {
                    Log.d("ProvisioningManager", "Skipping Samsung OEM dual/secure container ($handle)")
                    continue
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        // In Android, only Managed Work Profiles support quiet mode check
                        userManager.isQuietModeEnabled(handle)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            if (!userManager.isProfile) {
                                continue
                            }
                        }

                        return handle
                    } catch (e: Exception) {
                        Log.d("ProvisioningManager", "User $handle is not a valid managed work profile: ${e.message}")
                    }
                } else {
                    return handle
                }
            }
        } catch (e: Exception) {
            Log.e("ProvisioningManager", "Error querying profiles from LauncherApps", e)
        }
        return null
    }

    fun getPersonalUserHandle(): UserHandle {
        val myHandle = Process.myUserHandle()
        if (!userManager.isManagedProfile) {
            return myHandle
        }
        val candidateProfiles = launcherApps.profiles
        for (handle in candidateProfiles) {
            if (handle != myHandle) {
                return handle
            }
        }
        return myHandle
    }

    fun checkProvisioningState(): ProvisioningState {
        if (isWorkProfileActive()) {
            return ProvisioningState.ALREADY_PROVISIONED
        }

        return try {
            if (dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
                ProvisioningState.PROVISIONING_ALLOWED
            } else {
                ProvisioningState.NOT_PROVISIONED
            }
        } catch (e: Exception) {
            ProvisioningState.DEVICE_NOT_SUPPORTED
        }
    }

    fun getProvisioningIntent(): android.content.Intent {
        return android.content.Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                adminComponent
            )
        }
    }
}
