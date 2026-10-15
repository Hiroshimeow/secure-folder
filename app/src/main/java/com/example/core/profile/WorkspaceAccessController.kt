package com.example.core.profile

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import com.example.admin.SecureFolderAdminReceiver

data class WorkspaceAccessResult(
    val success: Boolean,
    val failedPackages: Set<String> = emptySet()
)

/**
 * Security boundary for cloned applications inside the managed profile.
 *
 * Work Profile itself stays enabled. Locking hides and suspends only the cloned packages, preserving
 * their APK/data while preventing launcher access, activities, notifications and recents.
 */
class WorkspaceAccessController(private val context: Context) {

    companion object {
        private const val TAG = "WorkspaceAccess"
        private const val PREFS_NAME = "workspace_access_state"
        private const val KEY_PROTECTED_PACKAGES = "protected_packages"
        private const val KEY_LOCKED = "workspace_locked"
        private const val KEY_GATE_EPOCH = "gate_epoch"
        private const val KEY_REGISTRY_VERSION = "registry_version"
        private const val REGISTRY_VERSION = 2
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val admin = SecureFolderAdminReceiver.getComponentName(context)

    fun isManagedProfileOwner(): Boolean {
        return userManager.isManagedProfile && try {
            dpm.isProfileOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun isLocked(): Boolean = prefs.getBoolean(KEY_LOCKED, true)

    fun protectedPackages(): Set<String> =
        prefs.getStringSet(KEY_PROTECTED_PACKAGES, emptySet())?.toSet().orEmpty()

    fun syncProtectedPackages(packages: Collection<String>) {
        val merged = (protectedPackages() + packages)
            .filter { it.isNotBlank() && it != context.packageName && isInstalled(it) }
            .toSet()
        prefs.edit().putStringSet(KEY_PROTECTED_PACKAGES, merged).apply()
    }

    fun registerProtectedPackage(packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        val updated = protectedPackages() + packageName
        prefs.edit().putStringSet(KEY_PROTECTED_PACKAGES, updated).apply()
        if (isLocked()) {
            setPackageLocked(packageName, true)
        }
    }

    fun unregisterProtectedPackage(packageName: String) {
        val updated = protectedPackages() - packageName
        prefs.edit().putStringSet(KEY_PROTECTED_PACKAGES, updated).apply()
    }

    fun lockAll(
        extraPackages: Collection<String> = emptyList(),
        gateEpoch: Long = 0L
    ): WorkspaceAccessResult {
        if (!isManagedProfileOwner()) return WorkspaceAccessResult(false)
        if (isStaleGateCommand(gateEpoch, locked = true)) return WorkspaceAccessResult(true)

        syncProtectedPackages(extraPackages)
        val editor = prefs.edit().putBoolean(KEY_LOCKED, true)
        if (gateEpoch > 0L) editor.putLong(KEY_GATE_EPOCH, gateEpoch)
        editor.commit()
        return applyLockedState(true)
    }

    fun unlockAll(
        extraPackages: Collection<String> = emptyList(),
        gateEpoch: Long = 0L
    ): WorkspaceAccessResult {
        if (!isManagedProfileOwner()) return WorkspaceAccessResult(false)
        if (isStaleGateCommand(gateEpoch, locked = false)) return WorkspaceAccessResult(true)

        syncProtectedPackages(extraPackages)
        if (gateEpoch > 0L) {
            // Persist the newest epoch before applying state so a delayed older LOCK arriving later
            // cannot overwrite this newer UNLOCK request.
            prefs.edit().putLong(KEY_GATE_EPOCH, gateEpoch).commit()
        }
        val result = applyLockedState(false)
        prefs.edit().putBoolean(KEY_LOCKED, !result.success).commit()
        return result
    }

    private fun isStaleGateCommand(gateEpoch: Long, locked: Boolean): Boolean {
        if (gateEpoch <= 0L) return false
        val appliedEpoch = prefs.getLong(KEY_GATE_EPOCH, 0L)
        if (gateEpoch >= appliedEpoch) return false

        Log.w(
            TAG,
            "Ignoring stale workspace command locked=$locked epoch=$gateEpoch latest=$appliedEpoch"
        )
        return true
    }

    fun enforceFailClosedOnProcessStart(): WorkspaceAccessResult {
        if (!isManagedProfileOwner()) return WorkspaceAccessResult(true)

        // One-time registry migration: only launchable, non-system, non-admin apps are eligible.
        // This intentionally replaces older broad discovery state that may have included unrelated
        // third-party packages in the managed profile.
        val discovered = discoverProtectablePackages()
        val currentVersion = prefs.getInt(KEY_REGISTRY_VERSION, 0)
        if (currentVersion < REGISTRY_VERSION) {
            prefs.edit()
                .putStringSet(KEY_PROTECTED_PACKAGES, discovered)
                .putInt(KEY_REGISTRY_VERSION, REGISTRY_VERSION)
                .commit()
            Log.i(TAG, "Migrated protected registry to v$REGISTRY_VERSION with ${discovered.size} packages")
        } else {
            syncProtectedPackages(discovered)
        }

        prefs.edit().putBoolean(KEY_LOCKED, true).commit()
        return applyLockedState(true)
    }

    private fun discoverProtectablePackages(): Set<String> {
        // Never hide/suspend another active administrator. Samsung/carrier builds can have
        // additional admins in the managed profile and they are part of the security substrate,
        // not user-cloned secret apps.
        val adminPackages = try {
            dpm.activeAdmins.orEmpty().map { it.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS
        )
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .filter { it.packageName !in adminPackages }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }
            .toSet()
    }

    private fun applyLockedState(locked: Boolean): WorkspaceAccessResult {
        val packages = protectedPackages().filter(::isInstalled).toSet()
        if (packages.isEmpty()) return WorkspaceAccessResult(true)

        // Samsung Android 13 can return package names from setPackagesSuspended() even when the
        // requested state was actually applied. Treat API return arrays as diagnostics only and
        // make the security verdict from read-back OS state.
        try {
            dpm.setPackagesSuspended(admin, packages.toTypedArray(), locked)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to change suspended state for protected packages", e)
        }

        packages.forEach { pkg ->
            try {
                dpm.setApplicationHidden(admin, pkg, locked)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to change hidden state for $pkg", e)
            }
        }

        val failures = packages.filterNot { pkg ->
            val hiddenMatches = try {
                dpm.isApplicationHidden(admin, pkg) == locked
            } catch (_: Exception) {
                false
            }
            val suspendedMatches = try {
                context.packageManager.isPackageSuspended(pkg) == locked
            } catch (_: Exception) {
                false
            }
            hiddenMatches && suspendedMatches
        }.toSet()

        Log.i(
            TAG,
            "Workspace package gate locked=$locked packages=${packages.size} verifiedFailures=${failures.size}"
        )
        return WorkspaceAccessResult(failures.isEmpty(), failures)
    }

    private fun setPackageLocked(packageName: String, locked: Boolean): Boolean {
        if (!isManagedProfileOwner() || !isInstalled(packageName)) return false
        return try {
            dpm.setPackagesSuspended(admin, arrayOf(packageName), locked)
            dpm.setApplicationHidden(admin, packageName, locked)
            dpm.isApplicationHidden(admin, packageName) == locked &&
                context.packageManager.isPackageSuspended(packageName) == locked
        } catch (e: Exception) {
            Log.e(TAG, "Unable to change package gate for $packageName", e)
            false
        }
    }

    private fun isInstalled(packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
            (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
        } catch (_: Exception) {
            false
        }
    }
}
