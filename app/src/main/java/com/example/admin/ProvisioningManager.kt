package com.example.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log

enum class ProfileEnvironment {
    MAIN_PROFILE,
    MANAGED_PROFILE,
    UNKNOWN
}

enum class ProvisioningState {
    NOT_PROVISIONED,
    PROVISIONING_ALLOWED,
    ALREADY_PROVISIONED,
    DEVICE_NOT_SUPPORTED
}

/**
 * Manages Android Enterprise Work Profile provisioning and status detection.
 * Enforces precise Work Profile identification (filtering out OEM users 95, 150 on Samsung devices).
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

    fun isProfileOwner(): Boolean {
        return try {
            dpm.isProfileOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }
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
     * Filters against LauncherApps profiles, filters out OEM clones (Samsung Dual Messenger user 95),
     * and verifies enterprise managed profile status.
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

                // Explicitly filter out Samsung Dual Messenger (User 95)
                val handleStr = handle.toString()
                if (handleStr.contains("95") || handle.hashCode() == 95) {
                    Log.d("ProvisioningManager", "Skipping Samsung Dual Messenger container ($handle)")
                    continue
                }

                // Verify quiet mode and profile semantics
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        // In Android, only Managed Work Profiles support quiet mode requests.
                        // Clone profiles and secondary personal users throw IllegalArgumentException.
                        userManager.isQuietModeEnabled(handle)

                        // Ensure user is not an unmanaged clone/guest profile
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
        if (isProfileOwner() || getWorkProfileUserHandle() != null) {
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

    /**
     * Builds the Intent to initiate Android OS Work Profile setup.
     */
    fun createProvisioningIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DISCLAIMER_HEADER, "Không gian Bảo mật Work Profile")
            }
        }
    }
}
