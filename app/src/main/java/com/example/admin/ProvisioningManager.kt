package com.example.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.example.core.profile.ProfileRole
import com.example.core.profile.WorkspaceState

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
 * Runtime Architecture:
 * - In Personal Profile (User 0): Acts as PERSONAL_CONTROLLER. It is NOT Profile Owner.
 *   Dispatches management commands to the Managed Executor and resolves apps via LauncherApps.
 * - In Work Profile: Acts as MANAGED_EXECUTOR and Profile Owner.
 */
class ProvisioningManager(private val context: Context) {

    companion object {
        private const val TAG = "ProvisioningManager"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val adminComponent: ComponentName = SecureFolderAdminReceiver.getComponentName(context)

    /**
     * Determines whether the current process is running in Personal Controller or Managed Executor mode.
     */
    fun getRole(): ProfileRole {
        return if (userManager.isManagedProfile) {
            ProfileRole.MANAGED_EXECUTOR
        } else {
            ProfileRole.PERSONAL_CONTROLLER
        }
    }

    fun getEnvironment(): ProfileEnvironment {
        return if (userManager.isManagedProfile) {
            ProfileEnvironment.MANAGED_PROFILE
        } else {
            ProfileEnvironment.MAIN_PROFILE
        }
    }

    /**
     * Checks if calling process is Profile Owner in its own profile.
     * Only true inside the Managed Work Profile!
     */
    fun isProfileOwner(): Boolean {
        return try {
            dpm.isProfileOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Semantic workspace state for Personal Controller UI.
     */
    fun getWorkspaceState(): WorkspaceState {
        if (getRole() == ProfileRole.MANAGED_EXECUTOR) {
            return if (isProfileOwner()) WorkspaceState.READY else WorkspaceState.ERROR
        }

        val workHandle = getWorkProfileUserHandle() ?: return WorkspaceState.NOT_PROVISIONED

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && userManager.isQuietModeEnabled(workHandle)) {
                WorkspaceState.PAUSED
            } else {
                WorkspaceState.READY
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking quiet mode on work handle", e)
            WorkspaceState.READY
        }
    }

    /**
     * Checks if the overall Work Profile architecture is active.
     */
    fun isWorkProfileActive(): Boolean {
        val state = getWorkspaceState()
        return state == WorkspaceState.READY || state == WorkspaceState.PAUSED
    }

    fun isAdminActive(): Boolean {
        return try {
            dpm.isAdminActive(adminComponent)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Discovers the paired Work Profile UserHandle through supported Android APIs.
     * Uses CrossProfileApps (API 28+) and LauncherApps profiles without hardcoded user IDs
     * or calling userManager.isProfile on the calling user.
     */
    fun getWorkProfileUserHandle(): UserHandle? {
        val myHandle = Process.myUserHandle()
        if (userManager.isManagedProfile) {
            return myHandle
        }

        // 1. CrossProfileApps API: targets user profiles where this app is paired
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val crossProfileApps = context.getSystemService(Context.CROSS_PROFILE_APPS_SERVICE) as? CrossProfileApps
                val targets = crossProfileApps?.targetUserProfiles
                if (!targets.isNullOrEmpty()) {
                    return targets.first()
                }
            } catch (e: Exception) {
                Log.d(TAG, "CrossProfileApps targetUserProfiles query note: ${e.message}")
            }
        }

        // 2. LauncherApps candidate query
        try {
            val candidateProfiles = launcherApps.profiles
            for (handle in candidateProfiles) {
                if (handle == myHandle) continue

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        // isQuietModeEnabled is specifically supported on managed work profiles
                        userManager.isQuietModeEnabled(handle)
                        return handle
                    } catch (e: Exception) {
                        Log.d(TAG, "Profile $handle is not a quiet-mode-compatible managed profile: ${e.message}")
                    }
                } else {
                    return handle
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying profiles from LauncherApps", e)
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

    /**
     * Builds the standard Android Enterprise Managed Profile provisioning intent.
     * Uses EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME (never EXTRA_DEVICE_ADMIN).
     */
    fun getProvisioningIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                adminComponent
            )
        }
    }
}
