package com.example.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Hardware-backed Android KeyStore Manager.
 * Generates and manages hardware-isolated AES-256 keys inside TEE / StrongBox.
 * Fail-closed: Never silently falls back to in-memory keys on production devices!
 */
class KeyStoreManager {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "SamsungSecureFolderMasterKey"
        private const val KEY_SIZE = 256
    }

    private val isRunningInTestEnvironment: Boolean by lazy {
        try {
            Build.FINGERPRINT.contains("robolectric", ignoreCase = true) ||
                    System.getProperty("java.vm.name")?.contains("Robolectric", ignoreCase = true) == true ||
                    Build.HARDWARE.contains("robolectric", ignoreCase = true) ||
                    System.getProperty("is.testing") == "true"
        } catch (e: Exception) {
            false
        }
    }

    private val keyStore: KeyStore? by lazy {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
        } catch (e: Exception) {
            if (isRunningInTestEnvironment) {
                null
            } else {
                throw KeyStoreException("AndroidKeyStore hardware provider failure on device: ${e.message}", e)
            }
        }
    }

    // JVM/Robolectric test fallback in-memory key - strictly restricted to unit tests
    private var jvmTestOnlyKey: SecretKey? = null

    /**
     * Retrieves the master SecretKey, or generates a new one if not present.
     * Enforces fail-closed semantics on production devices: Never uses in-memory keys on real devices!
     */
    @Synchronized
    fun getOrCreateMasterKey(): SecretKey {
        val ks = keyStore
        if (ks == null) {
            if (isRunningInTestEnvironment) {
                if (jvmTestOnlyKey == null) {
                    val kgen = KeyGenerator.getInstance("AES")
                    kgen.init(KEY_SIZE)
                    jvmTestOnlyKey = kgen.generateKey()
                }
                return jvmTestOnlyKey!!
            } else {
                throw KeyStoreException("AndroidKeyStore is unavailable. In-memory keys are strictly forbidden on production hardware.")
            }
        }

        if (!ks.containsAlias(KEY_ALIAS)) {
            generateMasterKey(ks)
        }
        val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey ?: generateMasterKey(ks)
    }

    private fun generateMasterKey(ks: KeyStore): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Deletes the key alias on vault reset.
     */
    @Synchronized
    fun deleteKey() {
        val ks = keyStore
        if (ks != null && ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
        }
        jvmTestOnlyKey = null
    }
}
