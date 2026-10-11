package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.example.admin.ProvisioningManager
import com.example.core.profile.ClonePackageStager
import com.example.core.profile.ProfileManager
import com.example.data.model.ClonedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages Installed Host Applications and Cloned Workspace Applications.
 * Ground-truth rule: Real OS Work Profile state is authoritative.
 * SharedPreferences only caches user intent; apps are never marked cloned without OS verification.
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
    private val clonePackageStager = ClonePackageStager(context)

    private val _clonedApps = MutableStateFlow<List<ClonedApp>>(emptyList())
    val clonedApps: StateFlow<List<ClonedApp>> = _clonedApps.asStateFlow()

    private val _availableHostApps = MutableStateFlow<List<ClonedApp>>(emptyList())
    val availableHostApps: StateFlow<List<ClonedApp>> = _availableHostApps.asStateFlow()

    init {
        loadClonedApps()
    }

    /**
     * Loads cloned apps from OS Managed Profile activities.
     * Deduplicates by package name and excludes self.
     */
    fun loadClonedApps() {
        val workActivities = profileManager.getClonedActivities()
        val list = mutableListOf<ClonedApp>()
        val activePackages = mutableSetOf<String>()

        for (activity in workActivities) {
            val pkg = activity.applicationInfo.packageName
            if (pkg == context.packageName) continue
            if (activePackages.contains(pkg)) continue // Deduplicate by package name
            activePackages.add(pkg)

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

        // Keep local cache in sync with verified OS truth
        prefs.edit().putStringSet(KEY_CLONED_PACKAGES, activePackages).apply()
        _clonedApps.value = list.sortedBy { it.appLabel }
    }

    /**
     * Scans installed apps on the Personal Profile (User 0) that are available to clone.
     * Deduplicates by package name and excludes apps already cloned.
     */
    suspend fun refreshHostApps() = withContext(Dispatchers.IO) {
        val result = mutableListOf<ClonedApp>()
        val currentCloned = _clonedApps.value.map { it.packageName }.toSet()
        val addedPackages = mutableSetOf<String>()

        try {
            val activities = profileManager.getHostInstalledActivities()
            if (activities.isNotEmpty()) {
                for (act in activities) {
                    val pkg = act.applicationInfo.packageName
                    if (pkg == context.packageName || currentCloned.contains(pkg) || addedPackages.contains(pkg)) continue
                    addedPackages.add(pkg)

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
                // Fallback using package manager
                val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                for (info in installed) {
                    val pkg = info.packageName
                    if (pkg == context.packageName || currentCloned.contains(pkg) || addedPackages.contains(pkg)) continue
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
                    addedPackages.add(pkg)

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

    suspend fun stageClonePackage(packageName: String): ArrayList<Uri> {
        return clonePackageStager.stage(packageName)
    }

    fun cleanupStagedClone(packageName: String) {
        clonePackageStager.cleanup(packageName)
    }

    /**
     * Verifies against real OS state whether a package is installed in the Managed Profile.
     */
    fun isPackageInWorkProfile(packageName: String): Boolean {
        val activities = profileManager.getClonedActivities()
        return activities.any { it.applicationInfo.packageName == packageName }
    }

    /**
     * Verifies that the clone operation actually succeeded in OS truth before updating state.
     * If verified: updates cloned list and refreshes host apps.
     * If not verified: preserves app in available host list and returns false.
     */
    suspend fun verifyAndApplyClone(packageName: String): Boolean = withContext(Dispatchers.IO) {
        loadClonedApps()
        val verified = isPackageInWorkProfile(packageName)
        if (verified) {
            refreshHostApps()
        }
        verified
    }

    /**
     * Verifies that the remove operation actually removed the package from OS truth.
     * If verified: removes from cloned list and restores to host apps.
     * If removal failed: preserves app in cloned list and returns false.
     */
    suspend fun verifyAndApplyRemoval(packageName: String): Boolean = withContext(Dispatchers.IO) {
        loadClonedApps()
        val stillExists = isPackageInWorkProfile(packageName)
        val success = !stillExists
        if (success) {
            refreshHostApps()
        }
        success
    }

    /**
     * Launches a cloned application instance strictly in its isolated profile.
     * Fail-closed: Never falls back to personal profile (User 0)!
     */
    fun launchClonedApp(app: ClonedApp, bounds: Rect? = null): Boolean {
        return profileManager.launchAppInWorkProfile(
            packageName = app.packageName,
            componentClassName = app.componentClassName,
            sourceBounds = bounds
        )
    }
}
