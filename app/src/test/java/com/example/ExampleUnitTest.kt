package com.example

import com.example.core.session.SessionManager
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun sessionManager_lifecycleAndRevocation() {
        val sessionManager = SessionManager()
        assertFalse(sessionManager.isAuthenticated.value)

        val sessionId1 = sessionManager.startSession()
        assertTrue(sessionManager.isAuthenticated.value)
        assertTrue(sessionManager.isSessionValid(sessionId1))

        // Lock / Relock occurs
        sessionManager.invalidateSession()
        assertFalse(sessionManager.isAuthenticated.value)
        assertFalse(sessionManager.isSessionValid(sessionId1))

        // New unlock session creates distinct session token
        val sessionId2 = sessionManager.startSession()
        assertTrue(sessionManager.isSessionValid(sessionId2))
        assertFalse(sessionManager.isSessionValid(sessionId1))
        assertNotEquals(sessionId1, sessionId2)
    }

    @Test
    fun pinRules_boundaryValidation() {
        fun isValidPin(pin: String): Boolean {
            return pin.length in 4..6 && pin.all { it.isDigit() }
        }

        assertTrue(isValidPin("1234"))
        assertTrue(isValidPin("12345"))
        assertTrue(isValidPin("123456"))
        assertFalse(isValidPin("123"))
        assertFalse(isValidPin("1234567"))
        assertFalse(isValidPin("12a4"))
        assertFalse(isValidPin(""))
        assertFalse(isValidPin("abcd"))
    }
}
