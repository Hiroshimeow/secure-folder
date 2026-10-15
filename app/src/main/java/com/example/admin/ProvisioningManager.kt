package com.example.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.example.core.profile.ManagedExecutorActivity
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
     * Idempotently configures policy owned by this DPC inside the managed profile.
     * This is intentionally callable after app upgrades so existing profiles are migrated,
     * not only on the one-shot provisioning-complete callback.
     */
    fun ensureManagedProfileConfiguration(): Boolean {
        if (getRole() != ProfileRole.MANAGED_EXECUTOR || !isProfileOwner()) {
            return false
        }

        return try {
            dpm.setProfileName(adminComponent, SecureFolderAdminReceiver.WORKSPACE_PROFILE_NAME)
            dpm.setProfileEnabled(adminComponent)

            // Cloning is intentionally user-mediated through PackageInstaller. The managed profile
            // must permit the system's per-source "Install unknown apps" consent UI; this does not
            // grant any installer permission by itself.
            dpm.clearUserRestriction(
                adminComponent,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
            )

            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, ManagedExecutorActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            // The managed-profile package is a headless DPC/executor. Its normal launcher entry must
            // never provide a second door around the Personal Vault PIN.
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, com.example.MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            // Rebuild only this DPC's cross-profile filters so upgrades cannot retain stale routing.
            dpm.clearCrossProfileIntentFilters(adminComponent)
            val commandFilter = IntentFilter().apply {
                addAction(com.example.core.profile.CrossProfileBridge.ACTION_EXECUTE_COMMAND)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            // Android's constant name is counter-intuitive: MANAGED_CAN_ACCESS_PARENT is the
            // direction that lets an Intent SENT FROM the parent profile resolve an Activity IN
            // the managed profile. This is exactly our Personal Controller -> Managed Executor path.
            dpm.addCrossProfileIntentFilter(
                adminComponent,
                commandFilter,
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setCrossProfilePackages(adminComponent, setOf(context.packageName))
            }

            Log.i(TAG, "Managed profile policy configuration ensured")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure managed profile configuration", e)
            false
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
