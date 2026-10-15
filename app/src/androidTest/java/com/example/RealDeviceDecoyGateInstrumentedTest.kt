package com.example

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.session.VaultSessionMode
import com.example.viewmodel.LockViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealDeviceDecoyGateInstrumentedTest {

    private val app: SecureFolderApp
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun decoyRoleNeverUnlocksWorkspace_primaryRoleRestoresWorkspace() = runBlocking {
        try {
            assertTrue("Unable to establish locked boundary", app.profileManager.setProfileLocked(true))
            app.sessionManager.invalidateSession()
            app.settingsRepository.setLocked(true)

            val decoyVm = LockViewModel()
            decoyVm.unlockVault(VaultSessionMode.DECOY)

            assertTrue(
                "DECOY session did not open after workspace lock was verified",
                waitUntil(5_000L) {
                    !decoyVm.uiState.value.isLocked &&
                        app.sessionManager.sessionMode.value == VaultSessionMode.DECOY
                }
            )
            assertTrue("Expected DECOY session", app.sessionManager.isDecoySession())
            assertTrue(
                "Real cloned apps must remain hidden during DECOY session",
                visibleUserClonePackages().isEmpty()
            )

            decoyVm.lockVault()
            assertTrue(
                "Vault did not re-lock after DECOY session",
                waitUntil(5_000L) { decoyVm.uiState.value.isLocked }
            )

            val primaryVm = LockViewModel()
            primaryVm.unlockVault(VaultSessionMode.PRIMARY)

            assertTrue(
                "PRIMARY session did not open after workspace unlock was verified",
                waitUntil(5_000L) {
                    !primaryVm.uiState.value.isLocked &&
                        app.sessionManager.sessionMode.value == VaultSessionMode.PRIMARY
                }
            )
            assertTrue("Expected PRIMARY session", app.sessionManager.isPrimarySession())
            assertFalse(
                "PRIMARY session should restore at least one cloned app on the physical test device",
                visibleUserClonePackages().isEmpty()
            )
        } finally {
            app.sessionManager.invalidateSession()
            app.profileManager.setProfileLocked(true)
            app.settingsRepository.setLocked(true)
        }
    }

    private fun visibleUserClonePackages(): Set<String> {
        return app.profileManager.getClonedActivities()
            .asSequence()
            .filter { it.applicationInfo.packageName != app.packageName }
            .filter { (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.applicationInfo.packageName }
            .toSet()
    }

    private suspend fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(50)
        }
        return condition()
    }
}
