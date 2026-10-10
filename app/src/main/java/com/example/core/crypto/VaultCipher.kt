package com.example.core.crypto

import com.example.SecureFolderApp
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec

/**
 * High-performance AES-256-GCM authenticated cipher for Vault storage.
 * Ensures confidentiality and tamper-evident authentication tags.
 * P0.08 fix: Strictly gates cryptographic operations behind Session Authorization.
 */
class VaultCipher(private val keyStoreManager: KeyStoreManager) {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12 // 96-bit recommended for GCM
        private const val GCM_TAG_LENGTH = 128 // 128-bit authentication tag
    }

    private fun assertAuthorized() {
        val sessionMgr = SecureFolderApp.instance.sessionManager
        if (!sessionMgr.isAuthenticated.value) {
            throw SecurityException("Cryptographic access denied: Vault session is locked or unauthenticated")
        }
    }

    /**
     * Encrypts byte array and outputs [12-byte IV + ciphertext + tag].
     */
    fun encrypt(plainBytes: ByteArray): ByteArray {
        assertAuthorized()
        val secretKey = keyStoreManager.getOrCreateMasterKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv

        val cipherBytes = cipher.doFinal(plainBytes)
        val result = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(cipherBytes, 0, result, iv.size, cipherBytes.size)
        return result
    }

    /**
     * Decrypts byte array from [12-byte IV + ciphertext + tag].
     */
    fun decrypt(encryptedBytes: ByteArray): ByteArray {
        assertAuthorized()
        if (encryptedBytes.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Invalid encrypted payload: size too small")
        }
        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(encryptedBytes, 0, iv, 0, GCM_IV_LENGTH)

        val cipherBytesLength = encryptedBytes.size - GCM_IV_LENGTH
        val cipherBytes = ByteArray(cipherBytesLength)
        System.arraycopy(encryptedBytes, GCM_IV_LENGTH, cipherBytes, 0, cipherBytesLength)

        val secretKey = keyStoreManager.getOrCreateMasterKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(cipherBytes)
    }

    /**
     * Streams input into encrypted output.
     * Writes 12-byte IV first, then cipher stream.
     */
    fun encryptStream(input: InputStream, output: OutputStream) {
        assertAuthorized()
        val secretKey = keyStoreManager.getOrCreateMasterKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv

        output.write(iv)
        CipherOutputStream(output, cipher).use { cipherOut ->
            input.copyTo(cipherOut, bufferSize = 8192)
        }
    }

    /**
     * Streams encrypted input from disk and writes decrypted bytes to output.
     */
    fun decryptStream(input: InputStream, output: OutputStream) {
        assertAuthorized()
        val iv = ByteArray(GCM_IV_LENGTH)
        val bytesRead = input.read(iv)
        if (bytesRead != GCM_IV_LENGTH) {
            throw IllegalArgumentException("Failed to read GCM IV from stream")
        }

        val secretKey = keyStoreManager.getOrCreateMasterKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        CipherInputStream(input, cipher).use { cipherIn ->
            cipherIn.copyTo(output, bufferSize = 8192)
        }
    }

    /**
     * Convenience method to decrypt stream into memory byte array.
     */
    fun decryptToByteArray(input: InputStream): ByteArray {
        assertAuthorized()
        val byteOut = java.io.ByteArrayOutputStream()
        decryptStream(input, byteOut)
        return byteOut.toByteArray()
    }
}
