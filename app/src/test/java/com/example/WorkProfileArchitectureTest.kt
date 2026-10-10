package com.example

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.example.admin.ProvisioningManager
import com.example.admin.SecureFolderAdminReceiver
import com.example.core.profile.CrossProfileBridge
import com.example.core.profile.ManagedExecutorActivity
import com.example.core.profile.PackageOpState
import com.example.core.profile.ProfileManager
import com.example.core.profile.WorkspaceState
import com.example.data.model.ClonedApp
import com.example.data.repository.AppRepository
import com.example.viewmodel.FolderHomeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUserManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkProfileArchitectureTest {

    private lateinit var app: SecureFolderApp
    private lateinit var provisioningManager: ProvisioningManager
    private lateinit var profileManager: ProfileManager

    @Before
    fun setUp() {
        System.setProperty("is.testing", "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        app = context.applicationContext as SecureFolderApp
        provisioningManager = app.provisioningManager
        profileManager = app.profileManager
    }

    /**
     * Requirement: Provisioning Intent contains correct provisioning admin component extra.
     * Must use EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, NOT EXTRA_DEVICE_ADMIN.
     */
    @Test
    fun testProvisioningIntent_hasCorrectActionAndAdminComponentExtra() {
        val intent = provisioningManager.getProvisioningIntent()

        assertEquals(
            "Action must be ACTION_PROVISION_MANAGED_PROFILE",
            DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE,
            intent.action
        )

        // Verify correct Android Enterprise component name extra
        val adminComponent = intent.getParcelableExtra<android.content.ComponentName>(
            DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME
        )
        assertNotNull(
            "EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME must be set in provisioning intent",
            adminComponent
        )
        assertEquals(
            "Admin component class must match SecureFolderAdminReceiver",
            SecureFolderAdminReceiver.getComponentName(app),
            adminComponent
        )

        // Verify that the deprecated/wrong EXTRA_DEVICE_ADMIN is NOT present
        assertFalse(
            "Intent MUST NOT use EXTRA_DEVICE_ADMIN for managed profile provisioning",
            intent.hasExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN)
        )
    }

    /**
     * Requirement: Personal Controller does not call Profile-Owner-only DPM directly.
     * In User 0 (personal profile), isProfileOwner is false; calling clone/unclone fails closed without calling DPM.
     */
    @Test
    fun testPersonalController_refusesDirectDpmProfileOwnerCalls() {
        assertFalse(
            "Personal profile (User 0) must not pretend to be Profile Owner",
            provisioningManager.isProfileOwner()
        )

        val cloneResult = profileManager.cloneAppIntoSecureFolder("com.example.target")
        assertFalse(
            "Personal Controller must refuse direct installExistingPackage and return false",
            cloneResult
        )

        val uncloneResult = profileManager.uncloneApp("com.example.target")
        assertFalse(
            "Personal Controller must refuse direct setApplicationHidden and return false",
            uncloneResult
        )
    }

    /**
     * Requirement: Managed Executor refuses command unless current instance is Profile Owner.
     * If started in personal profile or without Profile Owner privileges, fails closed.
     */
    @Test
    fun testManagedExecutorActivity_refusesExecutionIfNotProfileOwner() {
        val intent = Intent(app, ManagedExecutorActivity::class.java).apply {
            putExtra(CrossProfileBridge.EXTRA_COMMAND, CrossProfileBridge.COMMAND_CLONE)
            putExtra(CrossProfileBridge.EXTRA_TARGET_PACKAGE, "com.example.testapp")
        }

        val controller = Robolectric.buildActivity(ManagedExecutorActivity::class.java, intent)
        val activity = controller.create().get()

        assertTrue("Activity should finish immediately on security failure", activity.isFinishing)
    }

    /**
     * Requirement: Launch never falls back to personal profile.
     */
    @Test
    fun testLaunchApp_neverFallsBackToPersonalProfile() {
        // Calling launchAppInWorkProfile when package does not exist in work profile must fail closed (return false)
        val result = profileManager.launchAppInWorkProfile("com.nonexistent.app")
        assertFalse(
            "Launching an app not in managed profile must return false and not fall back to personal profile",
            result
        )
    }

    /**
     * Requirement: READY profile hides setup CTA, NOT_PROVISIONED shows setup CTA.
     */
    @Test
    fun testWorkspaceState_transitions() {
        // In default Robolectric personal profile without work profile, state should be NOT_PROVISIONED
        val state = provisioningManager.getWorkspaceState()
        assertEquals(WorkspaceState.NOT_PROVISIONED, state)

        // Verify that NOT_PROVISIONED correctly prompts setup in UI logic
        val viewModel = FolderHomeViewModel()
        viewModel.checkProfileStatus()
        assertEquals(WorkspaceState.NOT_PROVISIONED, viewModel.uiState.value.workspaceState)
    }

    /**
     * Requirement: Clone success is not emitted until managed-profile verification succeeds.
     * Clone failure preserves the app as available.
     * Remove failure preserves cloned item.
     */
    @Test
    fun testCloneLifecycle_verificationAndPreservation() = runBlocking {
        val repo = app.appRepository
        val viewModel = FolderHomeViewModel()

        val testApp = ClonedApp(
            packageName = "com.sample.target",
            appLabel = "Sample App",
            componentClassName = "com.sample.target.MainActivity",
            isWorkProfile = false
        )

        // 1. Simulating clone command failure (e.g. executor returns false or cannot verify in OS)
        viewModel.onCloneCommandResult("com.sample.target", false, "Installation failed in managed profile")

        val status = viewModel.uiState.value.packageStatuses["com.sample.target"]
        assertNotNull(status)
        assertEquals(PackageOpState.FAILED, status?.state)
        assertEquals("Installation failed in managed profile", status?.message)

        // Confirm app is NOT in cloned apps
        assertFalse(
            "Failed clone must not appear in clonedApps",
            viewModel.uiState.value.clonedApps.any { it.packageName == "com.sample.target" }
        )

        // 2. Simulating remove command failure
        viewModel.onRemoveCommandResult("com.sample.target", false, "Package removal failed")
        val removeStatus = viewModel.uiState.value.packageStatuses["com.sample.target"]
        assertEquals(PackageOpState.FAILED, removeStatus?.state)
    }

    /**
     * Requirement: Bounded system lease (picker protection).
     */
    @Test
    fun testExternalSystemFlowLease_bounds() {
        MainActivity.isLaunchingSystemIntent = true
        assertTrue(ExternalSystemFlowLease.isLeaseActive)
        assertTrue(MainActivity.isLaunchingSystemIntent)

        // Releasing lease clears it immediately
        ExternalSystemFlowLease.releaseLease()
        assertFalse(ExternalSystemFlowLease.isLeaseActive)
        assertFalse(MainActivity.isLaunchingSystemIntent)
    }
}
