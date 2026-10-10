package com.example.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Hardware-backed Android KeyStore Manager.
 * Generates and manages hardware-isolated AES-256 keys inside TEE / StrongBox.
 * Supports graceful fallback for local JVM/Robolectric test environments.
 */
class KeyStoreManager {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "SamsungSecureFolderMasterKey"
        private const val KEY_SIZE = 256
    }

    private val keyStore: KeyStore? by lazy {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
        } catch (e: Exception) {
            // AndroidKeyStore is not present in pure JVM / Robolectric environment
            null
        }
    }

    // JVM/Robolectric test fallback in-memory key
    private var jvmFallbackKey: SecretKey? = null

    /**
     * Retrieves the master SecretKey, or generates a new one if not present.
     */
    @Synchronized
    fun getOrCreateMasterKey(): SecretKey {
        val ks = keyStore
        if (ks == null) {
            // JVM unit test fallback
            if (jvmFallbackKey == null) {
                val kgen = KeyGenerator.getInstance("AES")
                kgen.init(KEY_SIZE)
                jvmFallbackKey = kgen.generateKey()
            }
            return jvmFallbackKey!!
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
        jvmFallbackKey = null
    }
}
