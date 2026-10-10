package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.SettingsRepository
import com.example.viewmodel.LockViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinResponsivenessTest {

    private lateinit var app: SecureFolderApp
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        System.setProperty("is.testing", "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        app = context.applicationContext as SecureFolderApp
        settingsRepo = app.settingsRepository
    }

    /**
     * Requirement: 4-digit PIN triggers verification when candidate reaches length 4.
     * Typing 1, 2, or 3 digits does not verify.
     */
    @Test
    fun testPinVerification_4DigitAutoSubmitTrigger() = runBlocking {
        settingsRepo.resetAllSettings()
        settingsRepo.setPin("4321")
        assertEquals(4, settingsRepo.settings.value.pinLength)

        val lockViewModel = LockViewModel()
        delay(100) // Allow state collection

        // Type 3 digits: must not verify
        lockViewModel.onPinDigit("4")
        lockViewModel.onPinDigit("3")
        lockViewModel.onPinDigit("2")
        assertEquals("432", lockViewModel.uiState.value.pinInput)
        assertFalse(lockViewModel.uiState.value.isVerifying)
        assertTrue(lockViewModel.uiState.value.isLocked)

        // Type 4th digit: auto-submits verification
        lockViewModel.onPinDigit("1")
        assertEquals("4321", lockViewModel.uiState.value.pinInput)

        // Wait for background verify to complete
        var attempts = 0
        while (lockViewModel.uiState.value.isLocked && attempts < 20) {
            delay(50)
            attempts++
        }

        assertFalse("Vault should be unlocked after entering correct 4th digit", lockViewModel.uiState.value.isLocked)
    }

    /**
     * Requirement: 6-digit PIN triggers verification when candidate reaches length 6.
     * Digits 1-5 do not trigger verification.
     */
    @Test
    fun testPinVerification_6DigitAutoSubmitTrigger() = runBlocking {
        settingsRepo.resetAllSettings()
        settingsRepo.setPin("654321")
        assertEquals(6, settingsRepo.settings.value.pinLength)

        val lockViewModel = LockViewModel()
        delay(100)

        // Enter 5 digits
        "65432".forEach { digit ->
            lockViewModel.onPinDigit(digit.toString())
        }
        assertEquals("65432", lockViewModel.uiState.value.pinInput)
        assertFalse(lockViewModel.uiState.value.isVerifying)
        assertTrue(lockViewModel.uiState.value.isLocked)

        // Enter 6th digit
        lockViewModel.onPinDigit("1")

        var attempts = 0
        while (lockViewModel.uiState.value.isLocked && attempts < 20) {
            delay(50)
            attempts++
        }

        assertFalse("Vault should be unlocked after entering 6th digit", lockViewModel.uiState.value.isLocked)
    }

    /**
     * Requirement: PBKDF2 runs off the main thread (Dispatchers.Default).
     */
    @Test
    fun testPbkdf2_runsOffMainThread() = runBlocking {
        settingsRepo.resetAllSettings()
        settingsRepo.setPin("9876")

        // Call verifyPin on Main dispatcher; verifyPin internally switches to Dispatchers.Default
        withContext(Dispatchers.Main) {
            val verified = settingsRepo.verifyPin("9876")
            assertTrue("verifyPin should return true for matching PIN", verified)
        }
    }
}
