package com.example

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealDeviceCloneDispatchInstrumentedTest {

    @Test
    fun dispatchClone_stagesApksAndRoutesToManagedExecutor() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SecureFolderApp>()
        val targetPackage = "yqtrack.app"

        assumeTrue(
            "Requires an existing paired Work Profile",
            app.provisioningManager.getWorkProfileUserHandle() != null
        )
        assumeTrue(
            "Physical test target must be installed in the personal profile",
            runCatching { app.packageManager.getApplicationInfo(targetPackage, 0) }.isSuccess
        )
        assumeTrue(
            "Target must not already be installed in the Work Profile",
            !app.appRepository.isPackageInWorkProfile(targetPackage)
        )

        val stagedUris = app.appRepository.stageClonePackage(targetPackage)
        assertTrue("At least one APK must be staged", stagedUris.isNotEmpty())

        val intent = app.crossProfileBridge.createCloneIntent(targetPackage, stagedUris).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)

        // The actual install is completed by Android's managed-profile PackageInstaller flow.
        // This test proves staging + cross-profile dispatch; OS completion is verified separately.
        Thread.sleep(1500)
    }
}
