package com.example.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Session Boundary & Confidentiality Manager.
 * Governs active authorization lifetime, invalidating in-memory secrets and
 * async callbacks upon Lock, Panic, or Backgrounding.
 */
class SessionManager {

    private val _sessionId = MutableStateFlow(UUID.randomUUID().toString())
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    @Synchronized
    fun startSession(): String {
        val newId = UUID.randomUUID().toString()
        _sessionId.value = newId
        _isAuthenticated.value = true
        return newId
    }

    @Synchronized
    fun invalidateSession() {
        _sessionId.value = UUID.randomUUID().toString()
        _isAuthenticated.value = false
    }

    fun isSessionValid(targetId: String): Boolean {
        return _isAuthenticated.value && _sessionId.value == targetId
    }
}
