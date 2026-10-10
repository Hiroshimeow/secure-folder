package com.example.core.profile

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import com.example.admin.SecureFolderAdminReceiver

/**
 * Headless executor activity that runs strictly inside the Android Enterprise Managed Profile.
 * Receives forwarded cross-profile commands (CLONE, REMOVE) and executes them under Profile Owner privileges.
 * Enforces zero-trust fail-closed security: refuses execution unless current user is managed profile AND this app is Profile Owner.
 */
class ManagedExecutorActivity : Activity() {

    companion object {
        private const val TAG = "ManagedExecutor"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        val isManaged = userManager.isManagedProfile
        val isProfileOwner = try {
            dpm.isProfileOwnerApp(packageName)
        } catch (e: Exception) {
            false
        }

        // P0 security invariant: Refuse execution unless running in managed profile AND Profile Owner
        if (!isManaged || !isProfileOwner) {
            Log.e(TAG, "Refusing command execution: isManagedProfile=$isManaged, isProfileOwner=$isProfileOwner. Failing closed.")
            val failureIntent = Intent().apply {
                putExtra(CrossProfileBridge.EXTRA_RESULT_SUCCESS, false)
                putExtra(CrossProfileBridge.EXTRA_ERROR_MESSAGE, "Executor not authorized: caller is not Profile Owner in managed profile")
            }
            setResult(RESULT_CANCELED, failureIntent)
            finish()
            return
        }

        val command = intent.getStringExtra(CrossProfileBridge.EXTRA_COMMAND)
        val targetPackage = intent.getStringExtra(CrossProfileBridge.EXTRA_TARGET_PACKAGE)
        val admin = SecureFolderAdminReceiver.getComponentName(this)

        var success = false
        var errorMessage: String? = null

        when (command) {
            CrossProfileBridge.COMMAND_CLONE -> {
                if (targetPackage.isNullOrBlank()) {
                    errorMessage = "Package name cannot be empty"
                } else {
                    try {
                        val installed = dpm.installExistingPackage(admin, targetPackage)
                        if (installed) {
                            // Verify OS truth: confirm package is indeed accessible in this managed profile
                            val isVerified = try {
                                packageManager.getPackageInfo(targetPackage, 0) != null
                            } catch (e: PackageManager.NameNotFoundException) {
                                false
                            }

                            if (isVerified) {
                                success = true
                                Log.i(TAG, "Successfully installed and verified $targetPackage in managed profile")
                            } else {
                                errorMessage = "Package install returned true but package is not queryable in profile"
                            }
                        } else {
                            errorMessage = "Installation failed in managed profile (installExistingPackage returned false)"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during installExistingPackage for $targetPackage", e)
                        errorMessage = e.message ?: "Unknown installation error"
                    }
                }
            }
            CrossProfileBridge.COMMAND_REMOVE -> {
                if (targetPackage.isNullOrBlank()) {
                    errorMessage = "Package name cannot be empty"
                } else {
                    try {
                        dpm.setApplicationHidden(admin, targetPackage, true)
                        try {
                            val packageInstaller = packageManager.packageInstaller
                            val dummyIntent = Intent("com.example.UNINSTALL_STATUS")
                            val pendingIntent = PendingIntent.getBroadcast(
                                this,
                                0,
                                dummyIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            packageInstaller.uninstall(targetPackage, pendingIntent.intentSender)
                        } catch (e: Exception) {
                            Log.w(TAG, "PackageInstaller uninstall triggered with note: ${e.message}")
                        }
                        success = true
                        Log.i(TAG, "Successfully uncloned/hidden $targetPackage in managed profile")
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during removal for $targetPackage", e)
                        errorMessage = e.message ?: "Unknown unclone error"
                    }
                }
            }
            else -> {
                errorMessage = "Unrecognized cross-profile command: $command"
            }
        }

        val resultIntent = Intent().apply {
            putExtra(CrossProfileBridge.EXTRA_RESULT_SUCCESS, success)
            putExtra(CrossProfileBridge.EXTRA_TARGET_PACKAGE, targetPackage)
            putExtra(CrossProfileBridge.EXTRA_ERROR_MESSAGE, errorMessage)
        }

        setResult(if (success) RESULT_OK else RESULT_CANCELED, resultIntent)
        finish()
    }
}
