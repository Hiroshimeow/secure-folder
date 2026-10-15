package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealDeviceWorkspaceGateInstrumentedTest {

    private val app: SecureFolderApp
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun lockHidesCloneAppsWithoutStoppingWorkProfile_andUnlockRestoresThem() = runBlocking {
        val workHandle = app.provisioningManager.getWorkProfileUserHandle()
        assertNotNull("Physical-device test requires an existing managed profile", workHandle)

        try {
            assertTrue("Workspace unlock dispatch failed", app.profileManager.setProfileLocked(false))

            val initialPackages = waitForPackages(
                condition = { it.isNotEmpty() },
                timeoutMillis = 4_000L
            )
            assertTrue(
                "Physical-device gate test requires at least one non-system cloned app",
                initialPackages.isNotEmpty()
            )

            assertTrue("Workspace lock failed", app.profileManager.setProfileLocked(true))

            val hidden = waitUntil(timeoutMillis = 4_000L) {
                val visible = nonSystemClonedPackages()
                visible.intersect(initialPackages).isEmpty()
            }
            assertTrue("Protected clone apps remained launchable after lock", hidden)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val userManager = app.getSystemService(Context.USER_SERVICE) as UserManager
                assertFalse(
                    "Normal security path must hide+suspend packages without Quiet Mode",
                    userManager.isQuietModeEnabled(workHandle!!)
                )
            }

            assertTrue("Workspace unlock failed", app.profileManager.setProfileLocked(false))

            val restored = waitUntil(timeoutMillis = 4_000L) {
                nonSystemClonedPackages().containsAll(initialPackages)
            }
            assertTrue("Protected clone apps did not return after PRIMARY unlock", restored)
        } finally {
            // Security tests must leave the physical device fail-closed.
            app.profileManager.setProfileLocked(true)
        }
    }

    private fun waitForPackages(
        condition: (Set<String>) -> Boolean,
        timeoutMillis: Long
    ): Set<String> {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last = emptySet<String>()
        while (System.currentTimeMillis() < deadline) {
            last = nonSystemClonedPackages()
            if (condition(last)) return last
            Thread.sleep(100)
        }
        return last
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun nonSystemClonedPackages(): Set<String> {
        return app.profileManager.getClonedActivities()
            .asSequence()
            .filter { (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.applicationInfo.packageName }
            .filter { it != app.packageName }
            .toSet()
    }
}
