package com.example.core.profile

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import com.example.admin.ProvisioningManager
import com.example.admin.SecureFolderAdminReceiver

/**
 * Cross-Profile Bridge for interacting between Personal User 0 and Secure Folder.
 */
class CrossProfileBridge(
    private val context: Context,
    private val provisioningManager: ProvisioningManager
) {
    /**
     * Intent to open Device Admin settings if user needs to manually activate admin.
     */
    fun createDeviceAdminIntent(): Intent {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
            SecureFolderAdminReceiver.getComponentName(context)
        )
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Kích hoạt quyền quản trị để bảo vệ Thư mục Bảo mật, đóng băng tiến trình nền và cô lập dữ liệu ứng dụng."
        )
        return intent
    }

    /**
     * Detects if hardware-level Knox / StrongBox isolation is supported on device.
     */
    fun hasStrongBoxHardware(): Boolean {
        return context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
    }

    /**
     * Detects if device supports managed profiles.
     */
    fun supportsManagedProfiles(): Boolean {
        return context.packageManager.hasSystemFeature("android.software.managed_users")
    }
}
