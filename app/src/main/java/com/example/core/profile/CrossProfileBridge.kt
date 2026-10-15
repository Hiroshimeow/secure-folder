package com.example.core.profile

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.admin.ProvisioningManager
import com.example.admin.SecureFolderAdminReceiver

enum class CrossProfileDispatchState {
    DISPATCHED,
    CONSENT_REQUIRED,
    UNAVAILABLE
}

data class CrossProfileDispatchResult(
    val state: CrossProfileDispatchState,
    val consentIntent: Intent? = null,
    val errorMessage: String? = null
)

/**
 * Cross-profile bridge between the Personal Controller and the Managed Executor.
 *
 * Android 11+ uses CrossProfileApps.startActivity() with an explicit component. This avoids
 * depending on OEM implicit-intent resolution. The DPC allowlists this package with
 * setCrossProfilePackages(); the user grants cross-profile consent once through Android Settings.
 */
class CrossProfileBridge(
    private val context: Context,
    private val provisioningManager: ProvisioningManager
) {
    companion object {
        private const val TAG = "CrossProfileBridge"
        const val ACTION_EXECUTE_COMMAND = "com.example.action.EXECUTE_COMMAND"
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_RESULT_SUCCESS = "extra_result_success"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_APK_URIS = "extra_apk_uris"
        const val EXTRA_PROTECTED_PACKAGES = "extra_protected_packages"
        const val EXTRA_GATE_EPOCH = "extra_gate_epoch"
        const val EXTRA_GATE_RESULT_RECEIVER = "extra_gate_result_receiver"

        const val COMMAND_CLONE = "clone_package"
        const val COMMAND_REMOVE = "remove_package"
        const val COMMAND_LOCK_APPS = "lock_apps"
        const val COMMAND_UNLOCK_APPS = "unlock_apps"
    }

    fun createCloneIntent(packageName: String, apkUris: ArrayList<Uri>): Intent {
        return Intent(ACTION_EXECUTE_COMMAND).apply {
            putExtra(EXTRA_COMMAND, COMMAND_CLONE)
            putExtra(EXTRA_TARGET_PACKAGE, packageName)
            putParcelableArrayListExtra(EXTRA_APK_URIS, apkUris)
            addCategory(Intent.CATEGORY_DEFAULT)

            if (apkUris.isNotEmpty()) {
                clipData = ClipData.newUri(
                    context.contentResolver,
                    "clone_apk",
                    apkUris.first()
                ).also { clip ->
                    apkUris.drop(1).forEach { uri ->
                        clip.addItem(ClipData.Item(uri))
                    }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun createCloneIntent(packageName: String): Intent =
        createCloneIntent(packageName, arrayListOf())

    fun createRemoveIntent(packageName: String): Intent {
        return Intent(ACTION_EXECUTE_COMMAND).apply {
            putExtra(EXTRA_COMMAND, COMMAND_REMOVE)
            putExtra(EXTRA_TARGET_PACKAGE, packageName)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }

    fun createWorkspaceLockIntent(packageNames: Collection<String>, gateEpoch: Long): Intent {
        return Intent(ACTION_EXECUTE_COMMAND).apply {
            putExtra(EXTRA_COMMAND, COMMAND_LOCK_APPS)
            putStringArrayListExtra(EXTRA_PROTECTED_PACKAGES, ArrayList(packageNames.distinct()))
            putExtra(EXTRA_GATE_EPOCH, gateEpoch)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }

    fun createWorkspaceUnlockIntent(gateEpoch: Long): Intent {
        return Intent(ACTION_EXECUTE_COMMAND).apply {
            putExtra(EXTRA_COMMAND, COMMAND_UNLOCK_APPS)
            putExtra(EXTRA_GATE_EPOCH, gateEpoch)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }

    fun dispatchHeadless(commandIntent: Intent): Boolean {
        if (provisioningManager.getWorkProfileUserHandle() == null) return false
        return try {
            context.startActivity(
                Intent(commandIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Headless cross-profile dispatch unavailable: ${e.message}")
            false
        }
    }

    /**
     * Dispatches a command to this package's ManagedExecutorActivity in the paired Work Profile.
     * No numeric user IDs are used.
     */
    fun dispatchToManagedProfile(
        activity: Activity,
        commandIntent: Intent
    ): CrossProfileDispatchResult {
        val targetUser = provisioningManager.getWorkProfileUserHandle()
            ?: return CrossProfileDispatchResult(
                CrossProfileDispatchState.UNAVAILABLE,
                errorMessage = "Private Workspace is not available"
            )

        // DPC-owned cross-profile intent filters are the primary transport. The local executor
        // component is disabled in Personal/clone profiles, so a successful resolution here means
        // Android routed the command into the managed profile.
        try {
            activity.startActivity(commandIntent)
            return CrossProfileDispatchResult(CrossProfileDispatchState.DISPATCHED)
        } catch (filterError: Exception) {
            // Android 11+ also exposes CrossProfileApps. Use it only when interaction is already
            // allowed; a Profile Owner package is commonly ineligible for the user-consent request.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val crossProfileApps =
                    context.getSystemService(Context.CROSS_PROFILE_APPS_SERVICE) as? CrossProfileApps
                if (crossProfileApps != null && crossProfileApps.canInteractAcrossProfiles()) {
                    return try {
                        val explicitIntent = Intent(commandIntent).apply {
                            component = ComponentName(
                                context,
                                ManagedExecutorActivity::class.java
                            )
                        }
                        crossProfileApps.startActivity(
                            explicitIntent,
                            targetUser,
                            activity
                        )
                        CrossProfileDispatchResult(CrossProfileDispatchState.DISPATCHED)
                    } catch (e: Exception) {
                        CrossProfileDispatchResult(
                            CrossProfileDispatchState.UNAVAILABLE,
                            errorMessage = e.message ?: "Failed to start managed executor"
                        )
                    }
                }

                if (crossProfileApps != null && crossProfileApps.canRequestInteractAcrossProfiles()) {
                    val consentIntent = try {
                        crossProfileApps.createRequestInteractAcrossProfilesIntent()
                    } catch (_: Exception) {
                        null
                    }
                    if (consentIntent != null) {
                        return CrossProfileDispatchResult(
                            CrossProfileDispatchState.CONSENT_REQUIRED,
                            consentIntent = consentIntent
                        )
                    }
                }
            }

            return CrossProfileDispatchResult(
                CrossProfileDispatchState.UNAVAILABLE,
                errorMessage = filterError.message ?: "Cross-profile command is unavailable"
            )
        }
    }

    fun createDeviceAdminIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                SecureFolderAdminReceiver.getComponentName(context)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Kích hoạt quyền quản trị để bảo vệ Không gian Riêng tư và cách ly ứng dụng."
            )
        }
    }

    fun hasStrongBoxHardware(): Boolean {
        return context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_STRONGBOX_KEYSTORE
        )
    }

    fun supportsManagedProfiles(): Boolean {
        return context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_MANAGED_USERS
        )
    }
}
