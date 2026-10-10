package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.data.model.SecureVaultSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages persisted security settings, authenticated credential hashes, and disguise modes.
 * Enforces Salted PIN Hashing (SHA-256) and ephemeral memory-only lock state.
 */
class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "secure_folder_prefs"
        private const val KEY_PIN_SALT = "vault_pin_salt"
        private const val KEY_PIN_HASH = "vault_pin_hash"
        private const val KEY_PIN_SETUP = "is_pin_setup"
        private const val KEY_BIOMETRICS = "use_biometrics"
        private const val KEY_DISGUISE = "is_disguised"
        private const val KEY_AUTO_LOCK = "auto_lock_timeout"
        private const val HASH_ITERATIONS = 5000
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Ephemeral memory-only lock state: ALWAYS starts as true upon process start
    private var isLockedMemory: Boolean = true

    private val _settings = MutableStateFlow(
        SecureVaultSettings(
            isLocked = true,
            isPinSetup = prefs.getBoolean(KEY_PIN_SETUP, false),
            pinSalt = prefs.getString(KEY_PIN_SALT, "") ?: "",
            pinHash = prefs.getString(KEY_PIN_HASH, "") ?: "",
            useBiometrics = prefs.getBoolean(KEY_BIOMETRICS, true),
            isDisguisedAsCalculator = prefs.getBoolean(KEY_DISGUISE, false),
            autoLockTimeoutMinutes = prefs.getInt(KEY_AUTO_LOCK, 0)
        )
    )
    val settings: StateFlow<SecureVaultSettings> = _settings.asStateFlow()

    fun setLocked(locked: Boolean) {
        isLockedMemory = locked
        _settings.value = _settings.value.copy(isLocked = locked)
    }

    /**
     * Sets or changes the PIN with a cryptographically secure random salt and SHA-256 iterations.
     * Enforces digits-only validation (4-6 digits).
     */
    fun setPin(pin: String): Boolean {
        if (pin.length !in 4..6 || !pin.all { it.isDigit() }) {
            return false
        }
        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltBase64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val hashBase64 = computeHash(pin, saltBytes)

        prefs.edit()
            .putString(KEY_PIN_SALT, saltBase64)
            .putString(KEY_PIN_HASH, hashBase64)
            .putBoolean(KEY_PIN_SETUP, true)
            .apply()

        _settings.value = _settings.value.copy(
            isPinSetup = true,
            pinSalt = saltBase64,
            pinHash = hashBase64
        )
        return true
    }

    fun setUseBiometrics(use: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRICS, use).apply()
        _settings.value = _settings.value.copy(useBiometrics = use)
    }

    fun setDisguisedAsCalculator(disguised: Boolean) {
        prefs.edit().putBoolean(KEY_DISGUISE, disguised).apply()
        _settings.value = _settings.value.copy(isDisguisedAsCalculator = disguised)
    }

    fun setAutoLockTimeout(minutes: Int) {
        prefs.edit().putInt(KEY_AUTO_LOCK, minutes).apply()
        _settings.value = _settings.value.copy(autoLockTimeoutMinutes = minutes)
    }

    /**
     * Constant-time comparison of hashed PIN with stored salt.
     */
    fun verifyPin(inputPin: String): Boolean {
        if (!_settings.value.isPinSetup) return false
        val saltBase64 = _settings.value.pinSalt
        val expectedHash = _settings.value.pinHash
        if (saltBase64.isEmpty() || expectedHash.isEmpty()) return false

        val saltBytes = Base64.decode(saltBase64, Base64.NO_WRAP)
        val actualHash = computeHash(inputPin, saltBytes)

        return MessageDigest.isEqual(
            actualHash.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun computeHash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        var current = pin.toByteArray(Charsets.UTF_8) + salt
        for (i in 0 until HASH_ITERATIONS) {
            current = md.digest(current)
        }
        return Base64.encodeToString(current, Base64.NO_WRAP)
    }

    /**
     * Resets settings back to unconfigured initial state.
     */
    fun resetAllSettings() {
        prefs.edit().clear().apply()
        isLockedMemory = true
        _settings.value = SecureVaultSettings(
            isLocked = true,
            isPinSetup = false,
            pinSalt = "",
            pinHash = "",
            useBiometrics = true,
            isDisguisedAsCalculator = false,
            autoLockTimeoutMinutes = 0
        )
    }
}
