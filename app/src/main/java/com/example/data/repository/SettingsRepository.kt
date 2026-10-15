package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.core.session.VaultSessionMode
import com.example.data.model.SecureVaultSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Manages persisted security settings, authenticated credential hashes, and disguise modes.
 * Enforces PBKDF2WithHmacSHA256 key derivation with 100,000 iterations and random salt
 * to defeat offline brute-force attacks.
 */
class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "secure_folder_prefs"
        private const val KEY_PIN_SALT = "vault_pin_salt"
        private const val KEY_PIN_HASH = "vault_pin_hash"
        private const val KEY_PIN_LENGTH = "vault_pin_length"
        private const val KEY_PIN_SETUP = "is_pin_setup"
        private const val KEY_DECOY_PIN_SALT = "decoy_pin_salt"
        private const val KEY_DECOY_PIN_HASH = "decoy_pin_hash"
        private const val KEY_DECOY_PIN_LENGTH = "decoy_pin_length"
        private const val KEY_DECOY_PIN_SETUP = "is_decoy_pin_setup"
        private const val KEY_BIOMETRICS = "use_biometrics"
        private const val KEY_DISGUISE = "is_disguised"
        private const val KEY_AUTO_LOCK = "auto_lock_timeout"
        private const val KEY_SHAKE_TO_HIDE = "shake_to_hide"
        private const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
        private const val KEY_FACE_DOWN_TO_HIDE = "face_down_to_hide"
        private const val KEY_FACE_DOWN_DELAY = "face_down_delay_ms"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_LENGTH = 256
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Ephemeral memory-only lock state: ALWAYS starts as true upon process start
    private var isLockedMemory: Boolean = true

    private val _settings = MutableStateFlow(
        SecureVaultSettings(
            isLocked = true,
            isPinSetup = prefs.getBoolean(KEY_PIN_SETUP, false),
            pinLength = prefs.getInt(KEY_PIN_LENGTH, 4),
            pinSalt = prefs.getString(KEY_PIN_SALT, "") ?: "",
            pinHash = prefs.getString(KEY_PIN_HASH, "") ?: "",
            isDecoyPinSetup = prefs.getBoolean(KEY_DECOY_PIN_SETUP, false),
            decoyPinLength = prefs.getInt(KEY_DECOY_PIN_LENGTH, prefs.getInt(KEY_PIN_LENGTH, 4)),
            decoyPinSalt = prefs.getString(KEY_DECOY_PIN_SALT, "") ?: "",
            decoyPinHash = prefs.getString(KEY_DECOY_PIN_HASH, "") ?: "",
            useBiometrics = prefs.getBoolean(KEY_BIOMETRICS, true),
            isDisguisedAsCalculator = prefs.getBoolean(KEY_DISGUISE, false),
            autoLockTimeoutMinutes = prefs.getInt(KEY_AUTO_LOCK, 0),
            shakeToHideEnabled = prefs.getBoolean(KEY_SHAKE_TO_HIDE, false),
            shakeSensitivity = prefs.getInt(KEY_SHAKE_SENSITIVITY, 2),
            faceDownToHideEnabled = prefs.getBoolean(KEY_FACE_DOWN_TO_HIDE, false),
            faceDownDelayMillis = prefs.getLong(KEY_FACE_DOWN_DELAY, 1000L)
        )
    )
    val settings: StateFlow<SecureVaultSettings> = _settings.asStateFlow()

    fun setLocked(locked: Boolean) {
        isLockedMemory = locked
        _settings.value = _settings.value.copy(isLocked = locked)
    }

    /**
     * Sets or changes the PIN with a cryptographically secure random salt and PBKDF2.
     * Enforces digits-only validation (4-6 digits).
     * Automatically computes on IO/Default dispatcher and persists pinLength.
     */
    suspend fun setPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        if (pin.length !in 4..6 || !pin.all { it.isDigit() }) {
            return@withContext false
        }

        val previous = _settings.value
        if (verifyStoredCredential(
                pin,
                previous.isDecoyPinSetup,
                previous.decoyPinLength,
                previous.decoyPinSalt,
                previous.decoyPinHash
            )
        ) {
            // Primary and decoy credentials must never be ambiguous.
            return@withContext false
        }

        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltBase64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val hashBase64 = computeHash(pin, saltBytes)

        val decoyMustReset = previous.isDecoyPinSetup && previous.decoyPinLength != pin.length
        val editor = prefs.edit()
            .putString(KEY_PIN_SALT, saltBase64)
            .putString(KEY_PIN_HASH, hashBase64)
            .putInt(KEY_PIN_LENGTH, pin.length)
            .putBoolean(KEY_PIN_SETUP, true)
        if (decoyMustReset) {
            editor.remove(KEY_DECOY_PIN_SALT)
                .remove(KEY_DECOY_PIN_HASH)
                .remove(KEY_DECOY_PIN_LENGTH)
                .putBoolean(KEY_DECOY_PIN_SETUP, false)
        }
        editor.apply()

        _settings.value = previous.copy(
            isPinSetup = true,
            pinLength = pin.length,
            pinSalt = saltBase64,
            pinHash = hashBase64,
            isDecoyPinSetup = if (decoyMustReset) false else previous.isDecoyPinSetup,
            decoyPinLength = if (decoyMustReset) pin.length else previous.decoyPinLength,
            decoyPinSalt = if (decoyMustReset) "" else previous.decoyPinSalt,
            decoyPinHash = if (decoyMustReset) "" else previous.decoyPinHash
        )
        true
    }

    suspend fun setDecoyPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        val settings = _settings.value
        if (!settings.isPinSetup || pin.length != settings.pinLength || !pin.all { it.isDigit() }) {
            return@withContext false
        }
        if (verifyPin(pin)) return@withContext false

        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltBase64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val hashBase64 = computeHash(pin, saltBytes)

        prefs.edit()
            .putString(KEY_DECOY_PIN_SALT, saltBase64)
            .putString(KEY_DECOY_PIN_HASH, hashBase64)
            .putInt(KEY_DECOY_PIN_LENGTH, pin.length)
            .putBoolean(KEY_DECOY_PIN_SETUP, true)
            .apply()

        _settings.value = settings.copy(
            isDecoyPinSetup = true,
            decoyPinLength = pin.length,
            decoyPinSalt = saltBase64,
            decoyPinHash = hashBase64
        )
        true
    }

    fun clearDecoyPin() {
        prefs.edit()
            .remove(KEY_DECOY_PIN_SALT)
            .remove(KEY_DECOY_PIN_HASH)
            .remove(KEY_DECOY_PIN_LENGTH)
            .putBoolean(KEY_DECOY_PIN_SETUP, false)
            .apply()
        _settings.value = _settings.value.copy(
            isDecoyPinSetup = false,
            decoyPinLength = _settings.value.pinLength,
            decoyPinSalt = "",
            decoyPinHash = ""
        )
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

    fun setShakeToHideEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHAKE_TO_HIDE, enabled).apply()
        _settings.value = _settings.value.copy(shakeToHideEnabled = enabled)
    }

    fun setShakeSensitivity(level: Int) {
        val safeLevel = level.coerceIn(1, 3)
        prefs.edit().putInt(KEY_SHAKE_SENSITIVITY, safeLevel).apply()
        _settings.value = _settings.value.copy(shakeSensitivity = safeLevel)
    }

    fun setFaceDownToHideEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FACE_DOWN_TO_HIDE, enabled).apply()
        _settings.value = _settings.value.copy(faceDownToHideEnabled = enabled)
    }

    fun setFaceDownDelayMillis(delayMillis: Long) {
        val safeDelay = delayMillis.coerceIn(300L, 5000L)
        prefs.edit().putLong(KEY_FACE_DOWN_DELAY, safeDelay).apply()
        _settings.value = _settings.value.copy(faceDownDelayMillis = safeDelay)
    }

    /**
     * Constant-time comparison of hashed PIN with stored salt.
     * Offloaded strictly to background thread (Dispatchers.Default) to never freeze the UI.
     */
    suspend fun verifyPin(inputPin: String): Boolean = withContext(Dispatchers.Default) {
        val settings = _settings.value
        verifyStoredCredential(
            inputPin,
            settings.isPinSetup,
            settings.pinLength,
            settings.pinSalt,
            settings.pinHash
        )
    }

    suspend fun verifyPinRole(inputPin: String): VaultSessionMode? = withContext(Dispatchers.Default) {
        val settings = _settings.value
        val primaryMatch = verifyStoredCredential(
            inputPin,
            settings.isPinSetup,
            settings.pinLength,
            settings.pinSalt,
            settings.pinHash
        )
        val decoyMatch = verifyStoredCredential(
            inputPin,
            settings.isDecoyPinSetup,
            settings.decoyPinLength,
            settings.decoyPinSalt,
            settings.decoyPinHash
        )

        when {
            primaryMatch -> VaultSessionMode.PRIMARY
            decoyMatch -> VaultSessionMode.DECOY
            else -> null
        }
    }

    private fun verifyStoredCredential(
        inputPin: String,
        enabled: Boolean,
        expectedLength: Int,
        saltBase64: String,
        expectedHash: String
    ): Boolean {
        if (!enabled || inputPin.length != expectedLength || saltBase64.isEmpty() || expectedHash.isEmpty()) {
            return false
        }
        val saltBytes = Base64.decode(saltBase64, Base64.NO_WRAP)
        val actualHash = computeHash(inputPin, saltBytes)
        return MessageDigest.isEqual(
            actualHash.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun computeHash(pin: String, salt: ByteArray): String {
        return try {
            val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val hash = factory.generateSecret(spec).encoded
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Production must never silently downgrade PIN KDF strength.
            if (System.getProperty("is.testing") != "true") {
                throw IllegalStateException("PBKDF2WithHmacSHA256 unavailable on production device", e)
            }

            // Deterministic test-only fallback for JVM/Robolectric providers that lack PBKDF2.
            val md = MessageDigest.getInstance("SHA-256")
            var current = pin.toByteArray(Charsets.UTF_8) + salt
            for (i in 0 until 10000) {
                current = md.digest(current)
            }
            Base64.encodeToString(current, Base64.NO_WRAP)
        }
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
            pinLength = 4,
            pinSalt = "",
            pinHash = "",
            useBiometrics = true,
            isDisguisedAsCalculator = false,
            autoLockTimeoutMinutes = 0
        )
    }
}
