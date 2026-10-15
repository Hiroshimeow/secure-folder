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
        private const val PRIMARY_KEY_ALIAS = "SamsungSecureFolderMasterKey"
        private const val DECOY_KEY_ALIAS = "SamsungSecureFolderDecoyKey"
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

    // JVM/Robolectric test fallback keys - strictly restricted to unit tests.
    private var jvmTestOnlyPrimaryKey: SecretKey? = null
    private var jvmTestOnlyDecoyKey: SecretKey? = null

    @Synchronized
    fun getOrCreateMasterKey(): SecretKey = getOrCreateKey(PRIMARY_KEY_ALIAS, decoy = false)

    @Synchronized
    fun getOrCreateDecoyKey(): SecretKey = getOrCreateKey(DECOY_KEY_ALIAS, decoy = true)

    private fun getOrCreateKey(alias: String, decoy: Boolean): SecretKey {
        val ks = keyStore
        if (ks == null) {
            if (!isRunningInTestEnvironment) {
                throw KeyStoreException("AndroidKeyStore is unavailable. In-memory keys are strictly forbidden on production hardware.")
            }
            val existing = if (decoy) jvmTestOnlyDecoyKey else jvmTestOnlyPrimaryKey
            if (existing != null) return existing

            val kgen = KeyGenerator.getInstance("AES")
            kgen.init(KEY_SIZE)
            val generated = kgen.generateKey()
            if (decoy) jvmTestOnlyDecoyKey = generated else jvmTestOnlyPrimaryKey = generated
            return generated
        }

        if (!ks.containsAlias(alias)) {
            generateKey(alias)
        }
        val entry = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey ?: generateKey(alias)
    }

    private fun generateKey(alias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            alias,
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

    /** Deletes both logical-vault keys on a full reset. */
    @Synchronized
    fun deleteKey() {
        deleteAllKeys()
    }

    @Synchronized
    fun deleteAllKeys() {
        val ks = keyStore
        if (ks != null) {
            if (ks.containsAlias(PRIMARY_KEY_ALIAS)) ks.deleteEntry(PRIMARY_KEY_ALIAS)
            if (ks.containsAlias(DECOY_KEY_ALIAS)) ks.deleteEntry(DECOY_KEY_ALIAS)
        }
        jvmTestOnlyPrimaryKey = null
        jvmTestOnlyDecoyKey = null
    }
}
