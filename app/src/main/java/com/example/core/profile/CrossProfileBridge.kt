package com.example.core.profile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.admin.ProvisioningManager
import com.example.admin.SecureFolderAdminReceiver

/**
 * Cross-Profile Bridge coordinating commands between Personal Controller (User 0)
 * and Managed Executor (Work Profile).
 */
class CrossProfileBridge(
    private val context: Context,
    private val provisioningManager: ProvisioningManager
) {
    companion object {
        const val ACTION_EXECUTE_COMMAND = "com.example.action.EXECUTE_COMMAND"
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_RESULT_SUCCESS = "extra_result_success"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        const val COMMAND_CLONE = "clone_package"
        const val COMMAND_REMOVE = "remove_package"
    }

    /**
     * Builds an explicit cross-profile intent to request package cloning in the managed profile.
     */
    fun createCloneIntent(packageName: String): Intent {
        return Intent(ACTION_EXECUTE_COMMAND).apply {
            putExtra(EXTRA_COMMAND, COMMAND_CLONE)
            putExtra(EXTRA_TARGET_PACKAGE, packageName)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }

    /**
     * Builds an explicit cross-profile intent to request package removal in the managed profile.
     */
    fun createRemoveIntent(packageName: String): Intent {
        return Intent(ACTION_EXECUTE_COMMAND).apply {
            putExtra(EXTRA_COMMAND, COMMAND_REMOVE)
            putExtra(EXTRA_TARGET_PACKAGE, packageName)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }

    /**
     * Intent to open Device Admin settings if needed.
     */
    fun createDeviceAdminIntent(): Intent {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
            SecureFolderAdminReceiver.getComponentName(context)
        )
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Kích hoạt quyền quản trị để bảo vệ Không gian Riêng tư và cách ly ứng dụng."
        )
        return intent
    }

    /**
     * Detects if hardware-level Knox / StrongBox isolation is supported on device.
     */
    fun hasStrongBoxHardware(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    /**
     * Detects if device supports managed profiles.
     */
    fun supportsManagedProfiles(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
    }
}
