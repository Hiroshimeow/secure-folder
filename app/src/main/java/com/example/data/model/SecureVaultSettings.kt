package com.example.data.model

/**
 * Settings and state for Samsung Secure Folder.
 */
data class SecureVaultSettings(
    val isLocked: Boolean = true,
    val pinCode: String = "1234",
    val useBiometrics: Boolean = true,
    val isDisguisedAsCalculator: Boolean = false,
    val autoLockTimeoutMinutes: Int = 0 // 0 means immediately upon backgrounding
)
