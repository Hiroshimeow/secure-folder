package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.session.VaultSessionMode
import com.example.data.repository.SettingsRepository
import com.example.data.repository.VaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualVaultSecurityTest {

    private lateinit var app: SecureFolderApp
    private lateinit var settings: SettingsRepository
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        runBlocking {
            System.setProperty("is.testing", "true")
            val context = ApplicationProvider.getApplicationContext<Context>()
            app = context.applicationContext as SecureFolderApp
            settings = SettingsRepository(app)
            repository = VaultRepository(app, app.vaultCipher)

            app.sessionManager.invalidateSession()
            settings.resetAllSettings()
            repository.wipeAllVaultNamespaces()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            app.sessionManager.invalidateSession()
            repository.wipeAllVaultNamespaces()
            settings.resetAllSettings()
        }
    }

    @Test
    fun primaryAndDecoyPinsResolveToDifferentLogicalUsers() = runBlocking {
        assertTrue(settings.setPin("2468"))
        assertFalse("Decoy PIN must not equal primary PIN", settings.setDecoyPin("2468"))
        assertTrue(settings.setDecoyPin("1357"))

        assertEquals(VaultSessionMode.PRIMARY, settings.verifyPinRole("2468"))
        assertEquals(VaultSessionMode.DECOY, settings.verifyPinRole("1357"))
        assertNull(settings.verifyPinRole("0000"))
    }

    @Test
    fun primaryAndDecoyUseDifferentCryptographicKeys() {
        val plaintext = "primary-only-secret".toByteArray()

        app.sessionManager.startSession(VaultSessionMode.PRIMARY)
        val primaryCiphertext = app.vaultCipher.encrypt(plaintext)

        app.sessionManager.invalidateSession()
        app.sessionManager.startSession(VaultSessionMode.DECOY)
        try {
            app.vaultCipher.decrypt(primaryCiphertext)
            fail("DECOY identity must never decrypt PRIMARY ciphertext")
        } catch (_: SecurityException) {
            // Expected: distinct AndroidKeyStore/test key.
        }

        val decoyCiphertext = app.vaultCipher.encrypt("decoy-only-secret".toByteArray())

        app.sessionManager.invalidateSession()
        app.sessionManager.startSession(VaultSessionMode.PRIMARY)
        try {
            app.vaultCipher.decrypt(decoyCiphertext)
            fail("PRIMARY identity must never decrypt DECOY ciphertext")
        } catch (_: SecurityException) {
            // Expected.
        }
    }

    @Test
    fun primaryAndDecoyVaultIndexesAreIndependent() = runBlocking {
        app.sessionManager.startSession(VaultSessionMode.PRIMARY)
        assertTrue(
            repository.saveSecureNoteAtomic(
                existingId = null,
                title = "Primary Note",
                content = "primary payload"
            ).isSuccess
        )
        repository.loadVaultIndex()
        assertEquals(listOf("Primary Note"), repository.vaultItems.value.map { it.title })

        app.sessionManager.invalidateSession()
        app.sessionManager.startSession(VaultSessionMode.DECOY)
        repository.loadVaultIndex()
        assertTrue("Fresh DECOY vault must not reveal PRIMARY index", repository.vaultItems.value.isEmpty())

        assertTrue(
            repository.saveSecureNoteAtomic(
                existingId = null,
                title = "Decoy Note",
                content = "decoy payload"
            ).isSuccess
        )
        repository.loadVaultIndex()
        assertEquals(listOf("Decoy Note"), repository.vaultItems.value.map { it.title })

        app.sessionManager.invalidateSession()
        app.sessionManager.startSession(VaultSessionMode.PRIMARY)
        repository.loadVaultIndex()
        assertEquals(listOf("Primary Note"), repository.vaultItems.value.map { it.title })
    }
}
