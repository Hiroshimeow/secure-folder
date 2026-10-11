package com.example.core.profile

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import com.example.admin.SecureFolderAdminReceiver
import java.io.IOException

/**
 * Headless managed-profile executor.
 *
 * Clone uses a split-aware PackageInstaller session populated from URI-granted APKs staged by the
 * Personal Controller. Remove uses Android's user-mediated uninstall UI. Neither operation reports
 * success until PackageManager truth in this managed profile confirms the requested end state.
 */
class ManagedExecutorActivity : Activity() {

    companion object {
        private const val TAG = "ManagedExecutor"
        private const val ACTION_INSTALL_SESSION_STATUS =
            "com.example.action.INSTALL_SESSION_STATUS"

        private const val REQUEST_UNKNOWN_SOURCES = 4100
        private const val REQUEST_UNINSTALL = 4102

        private const val STATE_COMMAND = "state_command"
        private const val STATE_PACKAGE = "state_package"
        private const val STATE_APK_URIS = "state_apk_uris"
    }

    private var pendingCommand: String? = null
    private var pendingPackage: String? = null
    private var pendingApkUris: ArrayList<Uri> = arrayListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        restoreState(savedInstanceState, intent)

        if (!isAuthorizedManagedExecutor()) {
            Log.e(TAG, "Refusing execution outside managed Profile Owner runtime")
            finishWithResult(
                false,
                "Executor not authorized: not Profile Owner in managed profile"
            )
            return
        }

        if (intent.action == ACTION_INSTALL_SESSION_STATUS) {
            handleInstallSessionStatus(intent)
            return
        }

        if (savedInstanceState != null) {
            // Do not launch a duplicate installer/uninstaller after Activity recreation.
            return
        }

        when (pendingCommand) {
            CrossProfileBridge.COMMAND_CLONE -> startCloneFlow()
            CrossProfileBridge.COMMAND_REMOVE -> startRemoveFlow()
            else -> finishWithResult(
                false,
                "Unrecognized cross-profile command: $pendingCommand"
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restoreState(null, intent)

        if (!isAuthorizedManagedExecutor()) {
            finishWithResult(false, "Executor authorization lost")
            return
        }

        if (intent.action == ACTION_INSTALL_SESSION_STATUS) {
            handleInstallSessionStatus(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_COMMAND, pendingCommand)
        outState.putString(STATE_PACKAGE, pendingPackage)
        outState.putParcelableArrayList(STATE_APK_URIS, pendingApkUris)
        super.onSaveInstanceState(outState)
    }

    private fun restoreState(savedInstanceState: Bundle?, sourceIntent: Intent) {
        pendingCommand = savedInstanceState?.getString(STATE_COMMAND)
            ?: sourceIntent.getStringExtra(CrossProfileBridge.EXTRA_COMMAND)
            ?: sourceIntent.getStringExtra(STATE_COMMAND)
        pendingPackage = savedInstanceState?.getString(STATE_PACKAGE)
            ?: sourceIntent.getStringExtra(CrossProfileBridge.EXTRA_TARGET_PACKAGE)
            ?: sourceIntent.getStringExtra(STATE_PACKAGE)

        pendingApkUris = when {
            savedInstanceState != null -> getUriList(savedInstanceState, STATE_APK_URIS)
            else -> {
                // Cross-user framework URI fixup is applied to ClipData. Custom Parcelable URI
                // extras are not rewritten, so prefer ClipData after the intent crosses profiles.
                val clipUris = getClipUris(sourceIntent)
                if (clipUris.isNotEmpty()) {
                    clipUris
                } else {
                    getUriList(sourceIntent, CrossProfileBridge.EXTRA_APK_URIS)
                }
            }
        }
    }

    private fun isAuthorizedManagedExecutor(): Boolean {
        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return userManager.isManagedProfile && try {
            dpm.isProfileOwnerApp(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun startCloneFlow() {
        val target = pendingPackage
        if (target.isNullOrBlank()) {
            finishWithResult(false, "Package name cannot be empty")
            return
        }

        if (isPackageInstalledForCurrentProfile(target)) {
            finishWithResult(true, null)
            return
        }

        if (pendingApkUris.isEmpty()) {
            finishWithResult(false, "Clone payload contains no APK files")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            try {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                Log.i(TAG, "Requesting per-source install permission in managed profile")
                startActivityForResult(settingsIntent, REQUEST_UNKNOWN_SOURCES)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open install-source settings", e)
                finishWithResult(
                    false,
                    "Cannot request permission to install cloned apps: ${e.message}"
                )
                return
            }
        }

        createAndCommitInstallSession()
    }

    private fun createAndCommitInstallSession() {
        val target = pendingPackage
        if (target.isNullOrBlank()) {
            finishWithResult(false, "Package name cannot be empty")
            return
        }

        val installer = packageManager.packageInstaller
        var sessionId: Int? = null

        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(target)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
            }

            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                pendingApkUris.forEachIndexed { index, uri ->
                    val metadata = queryApkMetadata(uri, index)
                    contentResolver.openInputStream(uri).use { input ->
                        if (input == null) {
                            throw IOException("Cannot open staged APK URI: $uri")
                        }

                        session.openWrite(
                            metadata.first,
                            0L,
                            metadata.second
                        ).use { output ->
                            input.copyTo(output, bufferSize = 128 * 1024)
                            session.fsync(output)
                        }
                    }
                }

                val callback = Intent(this, ManagedExecutorActivity::class.java).apply {
                    action = ACTION_INSTALL_SESSION_STATUS
                    putExtra(STATE_COMMAND, CrossProfileBridge.COMMAND_CLONE)
                    putExtra(STATE_PACKAGE, target)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    sessionId,
                    callback,
                    flags
                )

                Log.i(
                    TAG,
                    "Committing split-aware PackageInstaller session=$sessionId " +
                        "package=$target apkCount=${pendingApkUris.size}"
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller session failed for $target", e)
            if (sessionId != null) {
                try {
                    installer.abandonSession(sessionId)
                } catch (_: Exception) {
                }
            }
            finishWithResult(
                false,
                e.message ?: "PackageInstaller session failed"
            )
        }
    }

    private fun queryApkMetadata(uri: Uri, index: Int): Pair<String, Long> {
        var displayName: String? = null
        var size = -1L

        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }

        val safeName = displayName
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: if (index == 0) "base.apk" else "split_$index.apk"

        return safeName to size
    }

    private fun handleInstallSessionStatus(statusIntent: Intent) {
        val status = statusIntent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val statusMessage = statusIntent.getStringExtra(
            PackageInstaller.EXTRA_STATUS_MESSAGE
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = getIntentExtra(
                    statusIntent,
                    Intent.EXTRA_INTENT
                )
                if (confirmation == null) {
                    finishWithResult(
                        false,
                        "Installer requested confirmation without confirmation Intent"
                    )
                    return
                }

                Log.i(TAG, "Launching Android package-install confirmation")
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(confirmation)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                val target = pendingPackage
                val verified = !target.isNullOrBlank() &&
                    isPackageInstalledForCurrentProfile(target)

                if (verified) {
                    Log.i(TAG, "PackageInstaller success verified for $target")
                    finishWithResult(true, null)
                } else {
                    finishWithResult(
                        false,
                        "Installer reported success but package is absent in Private Workspace"
                    )
                }
            }

            else -> {
                Log.w(
                    TAG,
                    "PackageInstaller failed status=$status message=$statusMessage"
                )
                finishWithResult(
                    false,
                    statusMessage ?: "Package installation failed (status=$status)"
                )
            }
        }
    }

    private fun startRemoveFlow() {
        val target = pendingPackage
        if (target.isNullOrBlank()) {
            finishWithResult(false, "Package name cannot be empty")
            return
        }

        if (!isPackageInstalledForCurrentProfile(target)) {
            finishWithResult(true, null)
            return
        }

        try {
            val uninstallIntent = Intent(
                Intent.ACTION_UNINSTALL_PACKAGE,
                Uri.parse("package:$target")
            ).apply {
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            Log.i(TAG, "Requesting managed-profile uninstall for $target")
            startActivityForResult(uninstallIntent, REQUEST_UNINSTALL)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start package uninstaller for $target", e)
            finishWithResult(false, e.message ?: "Unable to open package uninstaller")
        }
    }

    @Deprecated("Retained for user-mediated system settings/uninstall result compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_UNKNOWN_SOURCES -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    packageManager.canRequestPackageInstalls()
                ) {
                    createAndCommitInstallSession()
                } else {
                    finishWithResult(
                        false,
                        "Permission to install apps in Private Workspace was not granted"
                    )
                }
            }

            REQUEST_UNINSTALL -> {
                val target = pendingPackage
                if (target.isNullOrBlank()) {
                    finishWithResult(false, "Removal result missing target package")
                    return
                }

                val stillInstalled = isPackageInstalledForCurrentProfile(target)
                if (!stillInstalled) {
                    Log.i(TAG, "Managed-profile uninstall verified for $target")
                    finishWithResult(true, null)
                } else {
                    finishWithResult(
                        false,
                        if (resultCode == RESULT_CANCELED) {
                            "Removal was cancelled"
                        } else {
                            "Android uninstaller returned but package is still installed"
                        }
                    )
                }
            }
        }
    }

    private fun isPackageInstalledForCurrentProfile(targetPackage: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(targetPackage, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Package verification failed for $targetPackage: ${e.message}")
            false
        }
    }

    private fun finishWithResult(success: Boolean, errorMessage: String?) {
        val resultIntent = Intent().apply {
            putExtra(CrossProfileBridge.EXTRA_RESULT_SUCCESS, success)
            putExtra(CrossProfileBridge.EXTRA_TARGET_PACKAGE, pendingPackage)
            putExtra(CrossProfileBridge.EXTRA_ERROR_MESSAGE, errorMessage)
        }
        setResult(if (success) RESULT_OK else RESULT_CANCELED, resultIntent)
        finish()
    }

    private fun getClipUris(sourceIntent: Intent): ArrayList<Uri> {
        val clip = sourceIntent.clipData ?: return arrayListOf()
        val uris = ArrayList<Uri>(clip.itemCount)
        for (index in 0 until clip.itemCount) {
            clip.getItemAt(index).uri?.let(uris::add)
        }
        return uris
    }

    @Suppress("DEPRECATION")
    private fun getUriList(bundle: Bundle, key: String): ArrayList<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelableArrayList(key, Uri::class.java) ?: arrayListOf()
        } else {
            bundle.getParcelableArrayList(key) ?: arrayListOf()
        }
    }

    @Suppress("DEPRECATION")
    private fun getUriList(sourceIntent: Intent, key: String): ArrayList<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sourceIntent.getParcelableArrayListExtra(key, Uri::class.java) ?: arrayListOf()
        } else {
            sourceIntent.getParcelableArrayListExtra(key) ?: arrayListOf()
        }
    }

    @Suppress("DEPRECATION")
    private fun getIntentExtra(sourceIntent: Intent, key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sourceIntent.getParcelableExtra(key, Intent::class.java)
        } else {
            sourceIntent.getParcelableExtra(key)
        }
    }
}
