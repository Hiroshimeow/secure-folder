package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.SecureVaultSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages persisted security settings, authentication credentials, and disguise modes.
 */
class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "secure_folder_prefs"
        private const val KEY_PIN = "vault_pin"
        private const val KEY_BIOMETRICS = "use_biometrics"
        private const val KEY_DISGUISE = "is_disguised"
        private const val KEY_AUTO_LOCK = "auto_lock_timeout"
        private const val KEY_LOCKED = "is_locked"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        SecureVaultSettings(
            isLocked = prefs.getBoolean(KEY_LOCKED, true),
            pinCode = prefs.getString(KEY_PIN, "1234") ?: "1234",
            useBiometrics = prefs.getBoolean(KEY_BIOMETRICS, true),
            isDisguisedAsCalculator = prefs.getBoolean(KEY_DISGUISE, false),
            autoLockTimeoutMinutes = prefs.getInt(KEY_AUTO_LOCK, 0)
        )
    )
    val settings: StateFlow<SecureVaultSettings> = _settings.asStateFlow()

    fun setLocked(locked: Boolean) {
        prefs.edit().putBoolean(KEY_LOCKED, locked).apply()
        _settings.value = _settings.value.copy(isLocked = locked)
    }

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN, pin).apply()
        _settings.value = _settings.value.copy(pinCode = pin)
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

    fun verifyPin(inputPin: String): Boolean {
        return inputPin == _settings.value.pinCode
    }
}
