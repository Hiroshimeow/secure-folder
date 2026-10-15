package com.example.core.profile

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.example.admin.ProvisioningManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.example.admin.SecureFolderAdminReceiver

/**
 * Profile management engine.
 * Responsibilities:
 * - Quiet mode freezing / unfreezing of the managed profile.
 * - Deduplicated querying of host and managed-profile activities.
 * - Profile-aware launching strictly targeting the managed profile (fail-closed).
 * - Guarding Profile-Owner-only DPM APIs so Personal Controller does not invoke them illegally.
 */
class ProfileManager(
    private val context: Context,
    private val provisioningManager: ProvisioningManager
) {

    companion object {
        private const val TAG = "ProfileManager"
        private const val CONTROLLER_PREFS = "workspace_controller_state"
        private const val KEY_KNOWN_PACKAGES = "known_clone_packages"
        private const val KEY_GATE_EPOCH = "workspace_gate_epoch"
        private const val LEGACY_CLONE_PREFS = "secure_cloned_apps"
        private const val LEGACY_CLONE_KEY = "cloned_packages"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val adminComponent = SecureFolderAdminReceiver.getComponentName(context)
    private val crossProfileBridge = CrossProfileBridge(context, provisioningManager)
    private val controllerPrefs = context.getSharedPreferences(CONTROLLER_PREFS, Context.MODE_PRIVATE)

    private fun knownClonePackages(): Set<String> {
        val own = controllerPrefs.getStringSet(KEY_KNOWN_PACKAGES, emptySet())?.toSet().orEmpty()
        if (own.isNotEmpty()) return own

        // One-time migration from the pre-gate clone cache in the Personal profile.
        val legacy = context.getSharedPreferences(LEGACY_CLONE_PREFS, Context.MODE_PRIVATE)
            .getStringSet(LEGACY_CLONE_KEY, emptySet())
            ?.toSet()
            .orEmpty()
            .filter { it.isNotBlank() && it != context.packageName }
            .toSet()
        if (legacy.isNotEmpty()) {
            controllerPrefs.edit().putStringSet(KEY_KNOWN_PACKAGES, legacy).apply()
        }
        return legacy
    }

    fun registerWorkspacePackage(packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        controllerPrefs.edit()
            .putStringSet(KEY_KNOWN_PACKAGES, knownClonePackages() + packageName)
            .apply()
    }

    fun unregisterWorkspacePackage(packageName: String) {
        controllerPrefs.edit()
            .putStringSet(KEY_KNOWN_PACKAGES, knownClonePackages() - packageName)
            .apply()
    }

    private fun replaceKnownWorkspacePackages(packages: Collection<String>) {
        controllerPrefs.edit()
            .putStringSet(
                KEY_KNOWN_PACKAGES,
                packages.filter { it.isNotBlank() && it != context.packageName }.toSet()
            )
            .apply()
    }

    @Synchronized
    private fun nextGateEpoch(): Long {
        val previous = controllerPrefs.getLong(KEY_GATE_EPOCH, 0L)
        val next = maxOf(previous + 1L, System.currentTimeMillis())
        controllerPrefs.edit().putLong(KEY_GATE_EPOCH, next).commit()
        return next
    }

    /**
     * Pre-dispatches the confidentiality lock while MainActivity is still foreground.
     * Android 10+ can block Activity-based cross-profile dispatch after onStop(), so callers invoke
     * this from Activity.onUserLeaveHint(), which runs immediately before onPause(). Completion is
     * still verified later by setProfileLocked(true) against LauncherApps OS truth.
     */
    fun preDispatchWorkspaceLockFromForeground(): Boolean {
        if (provisioningManager.getWorkProfileUserHandle() == null) return true
        return try {
            val visible = currentVisibleClonePackages()
            visible.forEach(::registerWorkspacePackage)
            val expected = knownClonePackages()
            val epoch = nextGateEpoch()
            val intent = crossProfileBridge.createWorkspaceLockIntent(expected, epoch)
            val dispatched = crossProfileBridge.dispatchHeadless(intent)
            Log.i(
                TAG,
                "Foreground pre-dispatch workspace lock: dispatched=$dispatched packages=${expected.size} epoch=$epoch"
            )
            dispatched
        } catch (e: Exception) {
            Log.e(TAG, "Foreground pre-dispatch workspace lock failed", e)
            false
        }
    }

    private suspend fun isLockedByOsTruth(expectedPackages: Set<String>, attempts: Int = 10): Boolean {
        if (expectedPackages.isEmpty()) return true
        repeat(attempts) {
            if (currentVisibleClonePackages().intersect(expectedPackages).isEmpty()) return true
            delay(100)
        }
        return currentVisibleClonePackages().intersect(expectedPackages).isEmpty()
    }

    private suspend fun dispatchGateAndAwait(
        baseIntent: Intent,
        gateEpoch: Long,
        locked: Boolean
    ): Boolean {
        repeat(4) { attempt ->
            val ack = CompletableDeferred<Boolean>()
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val ackEpoch = resultData?.getLong(CrossProfileBridge.EXTRA_GATE_EPOCH, 0L) ?: 0L
                    if (ackEpoch == gateEpoch && !ack.isCompleted) {
                        ack.complete(resultCode == Activity.RESULT_OK)
                    }
                }
            }

            val command = Intent(baseIntent).apply {
                putExtra(CrossProfileBridge.EXTRA_GATE_RESULT_RECEIVER, receiver)
            }
            if (!crossProfileBridge.dispatchHeadless(command)) {
                if (attempt < 3) delay(100)
                return@repeat
            }

            val result = withTimeoutOrNull(1_800L) { ack.await() }
            if (result != null) {
                Log.i(
                    TAG,
                    "Workspace executor ACK locked=$locked epoch=$gateEpoch success=$result attempt=${attempt + 1}"
                )
                return result
            }

            Log.w(
                TAG,
                "Workspace executor ACK timeout locked=$locked epoch=$gateEpoch attempt=${attempt + 1}"
            )
        }
        return false
    }

    /**
     * Locks/unlocks cloned apps without shutting down the Work Profile.
     *
     * Normal path: managed DPC hides + suspends only protected clone packages.
     * Fallback: if a lock command cannot be dispatched, quiet mode is enabled fail-closed.
     */
    suspend fun setProfileLocked(locked: Boolean): Boolean = withContext(Dispatchers.Default) {
        // No managed profile means there are no cloned apps to expose. The local Vault can still be
        // used before Android Enterprise provisioning.
        provisioningManager.getWorkProfileUserHandle() ?: return@withContext true

        try {
            val visibleBefore = currentVisibleClonePackages()
            if (locked && visibleBefore.isNotEmpty()) {
                visibleBefore.forEach(::registerWorkspacePackage)
            }
            val expectedPackages = knownClonePackages()

            val gateEpoch = nextGateEpoch()
            val intent = if (locked) {
                crossProfileBridge.createWorkspaceLockIntent(expectedPackages, gateEpoch)
            } else {
                crossProfileBridge.createWorkspaceUnlockIntent(gateEpoch)
            }

            // Do not treat startActivity() as command completion. Samsung can accept a cross-profile
            // launch while an older translucent executor is still finishing and then drop/coalesce
            // the new activity. Await an explicit DPC ACK; retry the same epoch if no ACK arrives.
            val acknowledged = dispatchGateAndAwait(intent, gateEpoch, locked)
            if (!acknowledged) {
                // Home/background transitions may block this second Activity launch after the
                // foreground pre-dispatch already reached the managed DPC. For LOCK only, OS truth
                // is authoritative: if every protected app is already absent, the boundary is safe.
                if (locked && isLockedByOsTruth(expectedPackages)) {
                    Log.i(
                        TAG,
                        "Workspace lock verified from foreground pre-dispatch despite missing follow-up ACK: epoch=$gateEpoch"
                    )
                    return@withContext true
                }
                Log.e(TAG, "Managed executor did not ACK workspace command: locked=$locked epoch=$gateEpoch")
                return@withContext false
            }

            // The executor is asynchronous. LOCK is a confidentiality boundary and therefore
            // remains fail-closed: every known/visible cloned app must disappear from LauncherApps.
            if (locked) {
                if (expectedPackages.isEmpty()) {
                    delay(250)
                    Log.i(TAG, "Workspace lock completed with empty protected registry")
                    return@withContext true
                }

                repeat(25) {
                    delay(100)
                    val visible = currentVisibleClonePackages()
                    if (visible.intersect(expectedPackages).isEmpty()) {
                        Log.i(TAG, "Workspace gate OS-truth verified: locked=true packages=${expectedPackages.size}")
                        return@withContext true
                    }
                }

                Log.e(
                    TAG,
                    "Workspace lock verification timed out: expected=${expectedPackages.size} visible=${currentVisibleClonePackages().size}"
                )
                return@withContext false
            }

            // UNLOCK is an availability path, not a confidentiality boundary. The DPC itself
            // verifies unhide/unsuspend against installed packages. Personal-side registry can
            // contain tombstones from apps removed outside our UI, so reconcile it to observable
            // launcher truth instead of blocking PRIMARY authentication forever.
            if (expectedPackages.isEmpty()) {
                delay(250)
                replaceKnownWorkspacePackages(currentVisibleClonePackages())
                Log.i(TAG, "Workspace unlock completed with empty protected registry")
                return@withContext true
            }

            var lastVisible = emptySet<String>()
            var stableSamples = 0
            repeat(25) { attempt ->
                delay(100)
                val visible = currentVisibleClonePackages()
                if (visible.containsAll(expectedPackages)) {
                    replaceKnownWorkspacePackages(visible)
                    Log.i(TAG, "Workspace gate OS-truth verified: locked=false packages=${visible.size}")
                    return@withContext true
                }

                stableSamples = if (visible == lastVisible) stableSamples + 1 else 0
                lastVisible = visible

                // Once at least one real clone has returned and launcher truth is stable, any
                // missing entries are stale/uninstalled tombstones. Prune them and continue.
                if (attempt >= 4 && visible.isNotEmpty() && stableSamples >= 2) {
                    replaceKnownWorkspacePackages(visible)
                    Log.w(
                        TAG,
                        "Reconciled stale workspace registry on unlock: expected=${expectedPackages.size} visible=${visible.size}"
                    )
                    return@withContext true
                }
            }

            // If all previously-known packages were removed outside this app, an empty visible set
            // is still safe. PRIMARY may open with zero cloned apps; later refresh will repopulate
            // only OS-truth packages.
            val finalVisible = currentVisibleClonePackages()
            replaceKnownWorkspacePackages(finalVisible)
            Log.w(
                TAG,
                "Workspace unlock completed after registry reconciliation: expected=${expectedPackages.size} visible=${finalVisible.size}"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error changing workspace access state", e)
            false
        }
    }

    private fun currentVisibleClonePackages(): Set<String> = getClonedActivities()
        .asSequence()
        .filter { (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
        .map { it.applicationInfo.packageName }
        .filter { it != context.packageName }
        .toSet()

    /**
     * Direct silent cloning is deliberately unsupported for this personally-owned Work Profile.
     * DevicePolicyManager.installExistingPackage() requires an affiliated profile, which this
     * product model does not have. Clone/remove must go through ManagedExecutorActivity so Android
     * can mediate user consent and the result can be verified against package-manager truth.
     */
    fun cloneAppIntoSecureFolder(packageName: String): Boolean {
        Log.w(TAG, "Direct clone is unsupported for $packageName; use Managed Executor user-mediated flow")
        return false
    }

    fun uncloneApp(packageName: String): Boolean {
        Log.w(TAG, "Direct remove is unsupported for $packageName; use Managed Executor user-mediated flow")
        return false
    }

    /**
     * Queries all installed launchable activities in Personal Space (User 0).
     * Deduplicates by package name so multiple launcher activities do not cause duplicates.
     */
    fun getHostInstalledActivities(): List<LauncherActivityInfo> {
        return try {
            val personalHandle = provisioningManager.getPersonalUserHandle()
            val rawList = launcherApps.getActivityList(null, personalHandle)
            // Deduplicate by package name
            rawList.distinctBy { it.applicationInfo.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying host activities", e)
            emptyList()
        }
    }

    /**
     * Queries cloned activities inside the Secure Folder Work Profile.
     * Deduplicates by package name so multiple launcher activities from one package produce a single app row.
     */
    fun getClonedActivities(): List<LauncherActivityInfo> {
        val workHandle = provisioningManager.getWorkProfileUserHandle() ?: return emptyList()
        return try {
            val rawList = launcherApps.getActivityList(null, workHandle)
            // Deduplicate by package name
            rawList.distinctBy { it.applicationInfo.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying cloned activities", e)
            emptyList()
        }
    }

    /**
     * Launches an application instance strictly in the managed profile.
     * Fail-closed: Never falls back to personal profile (User 0)!
     */
    fun launchAppInWorkProfile(
        packageName: String,
        componentClassName: String? = null,
        sourceBounds: Rect? = null
    ): Boolean {
        val workHandle = provisioningManager.getWorkProfileUserHandle()
        if (workHandle == null) {
            Log.e(TAG, "Cannot launch app $packageName: Managed Work Profile handle is null.")
            return false
        }

        return try {
            val activities = launcherApps.getActivityList(packageName, workHandle)
            if (activities.isEmpty()) {
                Log.e(TAG, "No launchable activity found for $packageName in managed profile")
                return false
            }

            val targetActivity = if (!componentClassName.isNullOrBlank()) {
                activities.find { it.componentName.className == componentClassName } ?: activities.first()
            } else {
                activities.first()
            }

            launcherApps.startMainActivity(targetActivity.componentName, workHandle, sourceBounds, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName in isolated profile: ${e.message}", e)
            false
        }
    }

    fun launchApp(
        componentName: ComponentName,
        userHandle: UserHandle,
        sourceBounds: Rect? = null
    ): Boolean {
        return try {
            launcherApps.startMainActivity(componentName, userHandle, sourceBounds, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch main activity for ${componentName.packageName}", e)
            false
        }
    }
}
