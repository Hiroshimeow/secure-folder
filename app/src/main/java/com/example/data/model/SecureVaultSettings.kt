package com.example.data.model

/**
 * Settings and state for Secure Folder.
 * Enforces salted PIN hashing and initial setup enforcement.
 */
data class SecureVaultSettings(
    val isLocked: Boolean = true,
    val isPinSetup: Boolean = false,
    val pinSalt: String = "",
    val pinHash: String = "",
    val useBiometrics: Boolean = true,
    val isDisguisedAsCalculator: Boolean = false,
    val autoLockTimeoutMinutes: Int = 0 // 0 = Immediately on exit
)
