package com.example

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.SettingsRepository
import com.example.viewmodel.LockViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
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
        settingsRepo.resetAllSettings()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun testPinVerification_4DigitAutoSubmitTrigger() {
        runBlocking {
            assertTrue(settingsRepo.setPin("4321"))
        }

        val lockViewModel = createViewModelAndDrainMain()

        lockViewModel.onPinDigit("4")
        lockViewModel.onPinDigit("3")
        lockViewModel.onPinDigit("2")
        assertEquals("432", lockViewModel.uiState.value.pinInput)
        assertFalse(lockViewModel.uiState.value.isVerifying)
        assertTrue(lockViewModel.uiState.value.isLocked)

        lockViewModel.onPinDigit("1")
        assertEquals("4321", lockViewModel.uiState.value.pinInput)

        assertTrue(
            "Vault should unlock after correct 4-digit candidate",
            pumpMainUntil(timeoutMillis = 4_000) { !lockViewModel.uiState.value.isLocked }
        )
    }

    @Test
    fun testPinVerification_6DigitAutoSubmitTrigger() {
        runBlocking {
            assertTrue(settingsRepo.setPin("654321"))
        }

        val lockViewModel = createViewModelAndDrainMain()

        "65432".forEach { lockViewModel.onPinDigit(it.toString()) }
        assertEquals("65432", lockViewModel.uiState.value.pinInput)
        assertFalse(lockViewModel.uiState.value.isVerifying)
        assertTrue(lockViewModel.uiState.value.isLocked)

        lockViewModel.onPinDigit("1")

        assertTrue(
            "Vault should unlock after correct 6-digit candidate",
            pumpMainUntil(timeoutMillis = 4_000) { !lockViewModel.uiState.value.isLocked }
        )
    }

    @Test
    fun testPinDigitHandler_doesNotBlockOnPbkdf2() {
        runBlocking {
            assertTrue(settingsRepo.setPin("9876"))
        }

        val lockViewModel = createViewModelAndDrainMain()
        "987".forEach { lockViewModel.onPinDigit(it.toString()) }

        val started = System.nanoTime()
        lockViewModel.onPinDigit("6")
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(
            "Final digit handler must return immediately instead of running PBKDF2 inline; took ${elapsedMillis}ms",
            elapsedMillis < 100
        )
        assertTrue(
            "Asynchronous verification should eventually unlock",
            pumpMainUntil(timeoutMillis = 4_000) { !lockViewModel.uiState.value.isLocked }
        )
    }

    private fun createViewModelAndDrainMain(): LockViewModel {
        val viewModel = LockViewModel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return viewModel
    }

    private fun pumpMainUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        while (System.currentTimeMillis() < deadline) {
            mainLooper.idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        mainLooper.idle()
        return condition()
    }
}
