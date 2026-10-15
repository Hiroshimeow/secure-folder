package com.example.data.model

/**
 * Settings and state for Secure Folder.
 * Enforces salted PIN hashing and initial setup enforcement.
 */
data class SecureVaultSettings(
    val isLocked: Boolean = true,
    val isPinSetup: Boolean = false,
    val pinLength: Int = 4,
    val pinSalt: String = "",
    val pinHash: String = "",
    val isDecoyPinSetup: Boolean = false,
    val decoyPinLength: Int = 4,
    val decoyPinSalt: String = "",
    val decoyPinHash: String = "",
    val useBiometrics: Boolean = true,
    val isDisguisedAsCalculator: Boolean = false,
    val autoLockTimeoutMinutes: Int = 0, // 0 = Immediately on exit
    val shakeToHideEnabled: Boolean = false,
    val shakeSensitivity: Int = 2, // 1=low, 2=medium, 3=high sensitivity
    val faceDownToHideEnabled: Boolean = false,
    val faceDownDelayMillis: Long = 1000L
)
