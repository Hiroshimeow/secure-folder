package com.example.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class VaultSessionMode {
    PRIMARY,
    DECOY
}

/**
 * Session Boundary & Confidentiality Manager.
 *
 * Authentication is not a single boolean anymore: every authenticated session is bound to a
 * logical vault identity. PRIMARY can unlock the protected Work Profile apps; DECOY can access only
 * the isolated decoy vault and must never unlock the real workspace.
 */
class SessionManager {

    private val _sessionId = MutableStateFlow(UUID.randomUUID().toString())
    val sessionId: StateFlow<String> = _sessionId.asStateFlow()

    private val _sessionMode = MutableStateFlow<VaultSessionMode?>(null)
    val sessionMode: StateFlow<VaultSessionMode?> = _sessionMode.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    @Synchronized
    fun startSession(mode: VaultSessionMode = VaultSessionMode.PRIMARY): String {
        val newId = UUID.randomUUID().toString()
        _sessionId.value = newId
        _sessionMode.value = mode
        _isAuthenticated.value = true
        return newId
    }

    @Synchronized
    fun invalidateSession() {
        _sessionId.value = UUID.randomUUID().toString()
        _sessionMode.value = null
        _isAuthenticated.value = false
    }

    fun isSessionValid(targetId: String): Boolean {
        return _isAuthenticated.value && _sessionId.value == targetId
    }

    fun isPrimarySession(): Boolean {
        return _isAuthenticated.value && _sessionMode.value == VaultSessionMode.PRIMARY
    }

    fun isDecoySession(): Boolean {
        return _isAuthenticated.value && _sessionMode.value == VaultSessionMode.DECOY
    }
}
