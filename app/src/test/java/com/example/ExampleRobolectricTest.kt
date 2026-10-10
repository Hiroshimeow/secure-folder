package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.SettingsRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var app: SecureFolderApp

    @Before
    fun setUp() {
        System.setProperty("is.testing", "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        app = context.applicationContext as SecureFolderApp
    }

    @Test
    fun testAppNameStringResource() {
        val appName = app.getString(R.string.app_name)
        assertEquals("Secure Folder", appName)
    }

    @Test
    fun testSettingsRepository_setPinAndVerifyWithPBKDF2() {
        val settingsRepo = SettingsRepository(app)
        settingsRepo.resetAllSettings()

        assertFalse("Unconfigured vault should not have PIN setup", settingsRepo.settings.value.isPinSetup)

        // Invalid pins
        assertFalse("Non-digits should be rejected", settingsRepo.setPin("12a4"))
        assertFalse("Too short (< 4 digits) should be rejected", settingsRepo.setPin("123"))
        assertFalse("Too long (> 6 digits) should be rejected", settingsRepo.setPin("1234567"))

        // Valid pin
        assertTrue("Valid 4-digit PIN should succeed", settingsRepo.setPin("2580"))
        assertTrue("PIN setup flag should be true", settingsRepo.settings.value.isPinSetup)

        // Verify correct pin
        assertTrue("Correct PIN verification should succeed", settingsRepo.verifyPin("2580"))
        assertFalse("Incorrect PIN verification should fail", settingsRepo.verifyPin("0000"))

        // Reset
        settingsRepo.resetAllSettings()
        assertFalse("After reset, PIN setup should be false", settingsRepo.settings.value.isPinSetup)
    }

    @Test
    fun testVaultCipher_sessionEnforcementAndTamperingDetection() {
        val cipher = app.vaultCipher
        val sessionManager = app.sessionManager

        // When locked: cipher operations MUST throw SecurityException
        sessionManager.invalidateSession()
        assertFalse(sessionManager.isAuthenticated.value)

        val testPlaintext = "Top Secret Knox Document Data 2026".toByteArray(Charsets.UTF_8)

        try {
            cipher.encrypt(testPlaintext)
            fail("Encrypt while locked should have thrown SecurityException")
        } catch (e: SecurityException) {
            // Expected
        }

        // Unlock session
        val sessionId = sessionManager.startSession()
        assertTrue(sessionManager.isSessionValid(sessionId))

        // Encrypt stream
        val encryptedOut = ByteArrayOutputStream()
        cipher.encryptStream(ByteArrayInputStream(testPlaintext), encryptedOut)
        val ciphertext = encryptedOut.toByteArray()
        assertTrue("Ciphertext should contain headers and payload", ciphertext.isNotEmpty())

        // Decrypt stream
        val decryptedOut = ByteArrayOutputStream()
        cipher.decryptStream(ByteArrayInputStream(ciphertext), decryptedOut)
        val decryptedBytes = decryptedOut.toByteArray()
        assertArrayEquals("Decrypted bytes must match original plaintext", testPlaintext, decryptedBytes)

        // Tampering test: flip one bit in ciphertext
        val tamperedCiphertext = ciphertext.clone()
        tamperedCiphertext[tamperedCiphertext.size - 5] = (tamperedCiphertext[tamperedCiphertext.size - 5].toInt() xor 0xFF).toByte()

        try {
            val tamperedOut = ByteArrayOutputStream()
            cipher.decryptStream(ByteArrayInputStream(tamperedCiphertext), tamperedOut)
            fail("Tampered ciphertext should have failed AEAD authentication")
        } catch (e: SecurityException) {
            // Expected: AEAD verification failed
            assertTrue(e.message?.contains("tamper", ignoreCase = true) == true || e.message?.contains("failed", ignoreCase = true) == true)
        }

        // Lock session and verify immediate revocation
        sessionManager.invalidateSession()
        try {
            cipher.decrypt(ciphertext)
            fail("Decrypt after lock should fail-closed")
        } catch (e: SecurityException) {
            // Expected
        }
    }
}
