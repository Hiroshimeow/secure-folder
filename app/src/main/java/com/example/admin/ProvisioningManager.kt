package com.example.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

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
 */
class ProvisioningManager(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
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

    fun getWorkProfileUserHandle(): UserHandle? {
        val myHandle = Process.myUserHandle()
        for (handle in userManager.userProfiles) {
            if (handle != myHandle) {
                // This is the secondary/managed profile handle
                return handle
            }
        }
        return if (userManager.isManagedProfile) myHandle else null
    }

    fun getPersonalUserHandle(): UserHandle {
        val myHandle = Process.myUserHandle()
        if (!userManager.isManagedProfile) {
            return myHandle
        }
        for (handle in userManager.userProfiles) {
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
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DISCLAIMER_HEADER, "Samsung Secure Folder")
            }
        }
    }
}
