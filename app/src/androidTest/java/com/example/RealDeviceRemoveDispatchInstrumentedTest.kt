package com.example

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealDeviceRemoveDispatchInstrumentedTest {

    @Test
    fun dispatchRemove_routesToManagedExecutor() {
        val app = ApplicationProvider.getApplicationContext<SecureFolderApp>()
        val targetPackage = "com.cloudflare.onedotonedotonedotone"

        assumeTrue(
            "Requires an existing paired Work Profile",
            app.provisioningManager.getWorkProfileUserHandle() != null
        )
        assumeTrue(
            "Physical test target must already exist in Work Profile",
            app.appRepository.isPackageInWorkProfile(targetPackage)
        )

        val intent = app.crossProfileBridge.createRemoveIntent(targetPackage).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)

        // Completion is intentionally user-mediated by Android's uninstall UI.
        Thread.sleep(1200)
    }
}
