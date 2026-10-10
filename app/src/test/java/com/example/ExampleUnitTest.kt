package com.example

import com.example.core.session.SessionManager
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun sessionManager_invalidatesOldSessionTokenOnLock() {
        val sessionManager = SessionManager()
        val sessionId1 = sessionManager.startSession()

        assertTrue(sessionManager.isAuthenticated.value)
        assertTrue(sessionManager.isSessionValid(sessionId1))

        // Lock / Relock occurs
        sessionManager.invalidateSession()

        assertFalse(sessionManager.isAuthenticated.value)
        assertFalse(sessionManager.isSessionValid(sessionId1))

        // New unlock session
        val sessionId2 = sessionManager.startSession()
        assertTrue(sessionManager.isSessionValid(sessionId2))
        assertFalse(sessionManager.isSessionValid(sessionId1))
    }

    @Test
    fun pinValidation_strictDigitsEnforced() {
        val validPin1 = "1234"
        val validPin2 = "987654"
        val invalidPinNonDigit = "12a4"
        val invalidPinShort = "123"
        val invalidPinLong = "1234567"

        fun isValidPin(pin: String): Boolean {
            return pin.length in 4..6 && pin.all { it.isDigit() }
        }

        assertTrue(isValidPin(validPin1))
        assertTrue(isValidPin(validPin2))
        assertFalse(isValidPin(invalidPinNonDigit))
        assertFalse(isValidPin(invalidPinShort))
        assertFalse(isValidPin(invalidPinLong))
    }
}
