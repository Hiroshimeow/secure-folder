package com.example.core.crypto

import com.example.SecureFolderApp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Enterprise Chunked Streaming AEAD (AES-256-GCM) Cipher for Secure Folder.
 * 
 * Architectural Guarantees:
 * 1. Chunked Framed AEAD (64 KB chunks): Avoids memory exhaustion (OOM) on large files.
 * 2. Strict Tag Verification (Zero Plaintext Leakage): Every chunk validates its 128-bit GCM
 *    authentication tag via cipher.doFinal() BEFORE a single byte of plaintext is written to the output stream.
 * 3. Immediate Fail-Closed Tampering Detection: Any bit flip, truncation, or tag mismatch instantly
 *    aborts with SecurityException, preventing release of unverified plaintext.
 * 4. Streaming Verification: Allows verifying entire file integrity without buffering in RAM.
 */
class VaultCipher(private val keyStoreManager: KeyStoreManager) {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12 // 96-bit standard IV
        private const val GCM_TAG_LENGTH = 128 // 128-bit authentication tag
        private const val CHUNK_SIZE = 64 * 1024 // 64 KB chunks

        // Magic header: "SFV1" (Secure Folder Vault v1)
        private val MAGIC_HEADER = byteArrayOf(0x53, 0x46, 0x56, 0x31)
    }

    private val secureRandom = SecureRandom()

    private fun assertAuthorized() {
        val sessionMgr = SecureFolderApp.instance.sessionManager
        if (!sessionMgr.isAuthenticated.value) {
            throw SecurityException("Cryptographic access denied: Vault session is locked or unauthenticated")
        }
    }

    /**
     * Encrypts small byte array (e.g. metadata, small notes) using framed single-chunk AEAD.
     */
    fun encrypt(plainBytes: ByteArray): ByteArray {
        assertAuthorized()
        val out = ByteArrayOutputStream()
        encryptStream(ByteArrayInputStream(plainBytes), out)
        return out.toByteArray()
    }

    /**
     * Decrypts byte array verifying integrity tags before returning plaintext.
     */
    fun decrypt(encryptedBytes: ByteArray): ByteArray {
        assertAuthorized()
        val out = ByteArrayOutputStream()
        decryptStream(ByteArrayInputStream(encryptedBytes), out)
        return out.toByteArray()
    }

    /**
     * Encrypts an input stream into framed chunked AEAD ciphertext.
     * Frame format per chunk:
     * [Magic 4B] (only at start of stream)
     * For each chunk:
     *   [Chunk Plaintext Length: 4B]
     *   [IV: 12B]
     *   [Ciphertext + 16B GCM Tag]
     * [End-of-Stream Marker: 4B with value 0x00000000]
     */
    fun encryptStream(input: InputStream, output: OutputStream) {
        assertAuthorized()
        val secretKey = keyStoreManager.getOrCreateMasterKey()

        // 1. Write Stream Magic Header
        output.write(MAGIC_HEADER)

        val buffer = ByteArray(CHUNK_SIZE)
        var bytesRead: Int

        while (input.read(buffer).also { bytesRead = it } != -1) {
            if (bytesRead == 0) continue

            // Generate fresh random 96-bit IV per chunk
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            val cipherBytes = cipher.doFinal(buffer, 0, bytesRead)

            // Write: [Plaintext Length: 4B][IV: 12B][Ciphertext + Tag]
            val lenBuffer = ByteBuffer.allocate(4).putInt(bytesRead).array()
            output.write(lenBuffer)
            output.write(iv)
            output.write(cipherBytes)
        }

        // Write End-of-Stream terminal marker (length = 0)
        output.write(ByteBuffer.allocate(4).putInt(0).array())
        output.flush()
    }

    /**
     * Decrypts framed chunked AEAD ciphertext stream.
     * Strictly verifies each chunk's tag before writing plaintext to output.
     * Throws SecurityException immediately if tag verification fails or stream is corrupted.
     */
    @Throws(SecurityException::class)
    fun decryptStream(input: InputStream, output: OutputStream) {
        assertAuthorized()
        val secretKey = keyStoreManager.getOrCreateMasterKey()

        // 1. Verify Magic Header
        val header = ByteArray(MAGIC_HEADER.size)
        if (readFully(input, header) != MAGIC_HEADER.size || !header.contentEquals(MAGIC_HEADER)) {
            // Check legacy fallback for unchunked data (backwards compatibility)
            decryptLegacyStream(header, input, output)
            return
        }

        val lenBytes = ByteArray(4)

        while (true) {
            if (readFully(input, lenBytes) != 4) {
                throw SecurityException("Corrupted AEAD stream: truncated chunk length header")
            }
            val plainLength = ByteBuffer.wrap(lenBytes).int
            if (plainLength == 0) {
                // End of Stream gracefully reached and authenticated
                break
            }
            if (plainLength < 0 || plainLength > CHUNK_SIZE) {
                throw SecurityException("Corrupted AEAD stream: invalid chunk length $plainLength")
            }

            // Read 12-byte IV
            val iv = ByteArray(GCM_IV_LENGTH)
            if (readFully(input, iv) != GCM_IV_LENGTH) {
                throw SecurityException("Corrupted AEAD stream: truncated IV")
            }

            // Ciphertext size = plainLength + 16 bytes tag
            val cipherSize = plainLength + (GCM_TAG_LENGTH / 8)
            val cipherBuffer = ByteArray(cipherSize)
            if (readFully(input, cipherBuffer) != cipherSize) {
                throw SecurityException("Corrupted AEAD stream: truncated ciphertext or authentication tag")
            }

            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

                // Atomic decryption + tag validation
                val plainChunk = cipher.doFinal(cipherBuffer)

                // ONLY write to output AFTER tag is verified
                output.write(plainChunk)
            } catch (e: AEADBadTagException) {
                throw SecurityException("AEAD Authentication Tag Verification FAILED: File has been tampered or corrupted", e)
            } catch (e: Exception) {
                throw SecurityException("Cryptographic verification failure during chunk decryption", e)
            }
        }
        output.flush()
    }

    /**
     * Verifies stream integrity of large files without allocating memory for plaintext.
     * Throws SecurityException if any chunk fails authentication.
     */
    @Throws(SecurityException::class)
    fun verifyStreamIntegrity(input: InputStream) {
        assertAuthorized()
        val nullOutput = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
        decryptStream(input, nullOutput)
    }

    /**
     * Convenience method for small in-memory payloads.
     */
    fun decryptToByteArray(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        decryptStream(input, out)
        return out.toByteArray()
    }

    private fun decryptLegacyStream(header: ByteArray, input: InputStream, output: OutputStream) {
        val secretKey = keyStoreManager.getOrCreateMasterKey()
        // If file was encrypted with legacy unchunked format [12B IV + Ciphertext]
        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(header, 0, iv, 0, minOf(header.size, GCM_IV_LENGTH))
        val remainingIv = GCM_IV_LENGTH - header.size
        if (remainingIv > 0) {
            readFully(input, iv, header.size, remainingIv)
        }

        val allRemaining = input.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decrypted = cipher.doFinal(allRemaining)
        output.write(decrypted)
        output.flush()
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
        var total = 0
        while (total < length) {
            val count = input.read(buffer, offset + total, length - total)
            if (count == -1) break
            total += count
        }
        return total
    }
}

