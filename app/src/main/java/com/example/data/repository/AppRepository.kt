package com.example.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.example.admin.ProvisioningManager
import com.example.core.profile.ProfileManager
import com.example.data.model.ClonedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages Installed Host Applications and Cloned Secure Folder Applications.
 */
class AppRepository(
    private val context: Context,
    private val profileManager: ProfileManager,
    private val provisioningManager: ProvisioningManager
) {

    companion object {
        private const val TAG = "AppRepository"
        private const val PREFS_NAME = "secure_cloned_apps"
        private const val KEY_CLONED_PACKAGES = "cloned_packages"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val packageManager: PackageManager = context.packageManager

    private val _clonedApps = MutableStateFlow<List<ClonedApp>>(emptyList())
    val clonedApps: StateFlow<List<ClonedApp>> = _clonedApps.asStateFlow()

    private val _availableHostApps = MutableStateFlow<List<ClonedApp>>(emptyList())
    val availableHostApps: StateFlow<List<ClonedApp>> = _availableHostApps.asStateFlow()

    init {
        loadClonedApps()
    }

    /**
     * Loads cloned apps from persistence and Managed Profile activities.
     */
    fun loadClonedApps() {
        val savedPackages = prefs.getStringSet(KEY_CLONED_PACKAGES, emptySet()) ?: emptySet()
        val list = mutableListOf<ClonedApp>()

        // 1. Query actual Work Profile activities if provisioned
        val workActivities = profileManager.getClonedActivities()
        val workPackages = mutableSetOf<String>()

        for (activity in workActivities) {
            val pkg = activity.applicationInfo.packageName
            // Exclude self from cloned list
            if (pkg == context.packageName) continue
            workPackages.add(pkg)

            list.add(
                ClonedApp(
                    packageName = pkg,
                    appLabel = activity.label.toString(),
                    componentClassName = activity.componentName.className,
                    isWorkProfile = true,
                    isSystemApp = (activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    icon = activity.getBadgedIcon(0)
                )
            )
        }

        // 2. Only if provisioned as profile owner or work profile is active, verify cloned packages
        for (pkg in savedPackages) {
            if (workPackages.contains(pkg) || pkg == context.packageName) continue
            // Do not deceptively show non-work-profile apps as isolated if not in work profile
            if (workActivities.isNotEmpty()) {
                continue
            }
        }

        _clonedApps.value = list.sortedBy { it.appLabel }
    }

    /**
     * Scans all installed apps on personal profile (User 0) that can be cloned into Secure Folder.
     */
    suspend fun refreshHostApps() = withContext(Dispatchers.IO) {
        val result = mutableListOf<ClonedApp>()
        val currentCloned = _clonedApps.value.map { it.packageName }.toSet()

        try {
            // First try LauncherApps on personal user handle
            val personalHandle = provisioningManager.getPersonalUserHandle()
            val activities = profileManager.getHostInstalledActivities()

            if (activities.isNotEmpty()) {
                for (act in activities) {
                    val pkg = act.applicationInfo.packageName
                    if (pkg == context.packageName || currentCloned.contains(pkg)) continue

                    result.add(
                        ClonedApp(
                            packageName = pkg,
                            appLabel = act.label.toString(),
                            componentClassName = act.componentName.className,
                            isWorkProfile = false,
                            isSystemApp = (act.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            icon = act.getBadgedIcon(0)
                        )
                    )
                }
            } else {
                // Standard package manager query fallback
                val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                for (info in installed) {
                    val pkg = info.packageName
                    if (pkg == context.packageName || currentCloned.contains(pkg)) continue
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: continue

                    result.add(
                        ClonedApp(
                            packageName = pkg,
                            appLabel = packageManager.getApplicationLabel(info).toString(),
                            componentClassName = launchIntent.component?.className ?: "",
                            isWorkProfile = false,
                            isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            icon = packageManager.getApplicationIcon(info)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing host apps", e)
        }

        _availableHostApps.value = result.sortedBy { it.appLabel }
    }

    /**
     * Clones an application into the Secure Folder using DPC installExistingPackage.
     * P0.02 fix: Only records clone if system cloning actually succeeds!
     */
    suspend fun cloneApp(app: ClonedApp): Boolean = withContext(Dispatchers.IO) {
        val success = profileManager.cloneAppIntoSecureFolder(app.packageName)
        if (success) {
            val currentSaved = prefs.getStringSet(KEY_CLONED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
            currentSaved.add(app.packageName)
            prefs.edit().putStringSet(KEY_CLONED_PACKAGES, currentSaved).apply()

            loadClonedApps()
            refreshHostApps()
        }
        success
    }

    /**
     * Removes a cloned app from the Secure Folder (P1.02 fix: hides/uninstalls from profile).
     */
    suspend fun removeClonedApp(packageName: String) = withContext(Dispatchers.IO) {
        profileManager.uncloneApp(packageName)
        val currentSaved = prefs.getStringSet(KEY_CLONED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentSaved.remove(packageName)
        prefs.edit().putStringSet(KEY_CLONED_PACKAGES, currentSaved).apply()

        loadClonedApps()
        refreshHostApps()
    }

    /**
     * Launches a cloned application instance strictly in its isolated profile.
     * Fail-closed: Never falls back to personal profile (User 0)!
     */
    fun launchClonedApp(app: ClonedApp, bounds: Rect? = null): Boolean {
        val workHandle = provisioningManager.getWorkProfileUserHandle()
        if (workHandle == null) {
            Log.e(TAG, "Cannot launch app ${app.packageName}: Managed Work Profile is not provisioned or not found.")
            return false
        }
        val component = ComponentName(app.packageName, app.componentClassName)
        return profileManager.launchApp(component, workHandle, bounds)
    }
}
