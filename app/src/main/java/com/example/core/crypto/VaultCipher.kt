package com.example.core.crypto

import com.example.SecureFolderApp
import com.example.core.session.VaultSessionMode
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
 * Chunked streaming AEAD (AES-256-GCM) for the local vault.
 *
 * SFV2 properties:
 * - Android Keystore generates a fresh IV for every encryption operation.
 * - Each data frame authenticates stream id, sequence, plaintext length and total bytes before it.
 * - A final authenticated frame commits the final sequence and total plaintext length.
 * - Decryption is fail-closed on truncation, reordering, duplication, trailing bytes or tag failure.
 *
 * SFV1 and the original unframed format remain readable for migration compatibility.
 */
class VaultCipher(private val keyStoreManager: KeyStoreManager) {

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_TAG_BYTES = GCM_TAG_LENGTH / 8
        private const val CHUNK_SIZE = 64 * 1024
        private const val STREAM_ID_LENGTH = 16
        private const val FRAME_DATA: Byte = 1
        private const val FRAME_FINAL: Byte = 2

        private val MAGIC_V1 = byteArrayOf(0x53, 0x46, 0x56, 0x31)
        private val MAGIC_V2 = byteArrayOf(0x53, 0x46, 0x56, 0x32)
    }

    private val secureRandom = SecureRandom()

    private fun assertAuthorized() {
        val sessionMgr = SecureFolderApp.instance.sessionManager
        if (!sessionMgr.isAuthenticated.value || sessionMgr.sessionMode.value == null) {
            throw SecurityException("Cryptographic access denied: Vault session is locked or unauthenticated")
        }
    }

    private fun activeSecretKey() = when (SecureFolderApp.instance.sessionManager.sessionMode.value) {
        VaultSessionMode.PRIMARY -> keyStoreManager.getOrCreateMasterKey()
        VaultSessionMode.DECOY -> keyStoreManager.getOrCreateDecoyKey()
        null -> throw SecurityException("Cryptographic access denied: No active vault identity")
    }

    fun encrypt(plainBytes: ByteArray): ByteArray {
        assertAuthorized()
        val out = ByteArrayOutputStream()
        encryptStream(ByteArrayInputStream(plainBytes), out)
        return out.toByteArray()
    }

    fun decrypt(encryptedBytes: ByteArray): ByteArray {
        assertAuthorized()
        val out = ByteArrayOutputStream()
        decryptStream(ByteArrayInputStream(encryptedBytes), out)
        return out.toByteArray()
    }

    fun encryptStream(input: InputStream, output: OutputStream) {
        assertAuthorized()
        val secretKey = activeSecretKey()
        val streamId = ByteArray(STREAM_ID_LENGTH).also(secureRandom::nextBytes)

        output.write(MAGIC_V2)
        output.write(streamId)

        val buffer = ByteArray(CHUNK_SIZE)
        var sequence = 0L
        var totalPlainBytes = 0L

        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            if (bytesRead == 0) continue

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv ?: throw SecurityException("Encryption provider did not generate an IV")
            if (iv.size != GCM_IV_LENGTH) {
                throw SecurityException("Unexpected GCM IV length: ${iv.size}")
            }

            cipher.updateAAD(buildFrameAad(
                magic = MAGIC_V2,
                streamId = streamId,
                frameType = FRAME_DATA,
                sequence = sequence,
                plainLength = bytesRead,
                totalPlainBytes = totalPlainBytes
            ))
            val cipherBytes = cipher.doFinal(buffer, 0, bytesRead)

            output.write(ByteBuffer.allocate(4).putInt(bytesRead).array())
            output.write(ByteBuffer.allocate(8).putLong(sequence).array())
            output.write(iv)
            output.write(cipherBytes)

            totalPlainBytes += bytesRead
            sequence++
        }

        val finalCipher = Cipher.getInstance(TRANSFORMATION)
        finalCipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val finalIv = finalCipher.iv ?: throw SecurityException("Encryption provider did not generate a final-frame IV")
        if (finalIv.size != GCM_IV_LENGTH) {
            throw SecurityException("Unexpected final GCM IV length: ${finalIv.size}")
        }
        finalCipher.updateAAD(buildFrameAad(
            magic = MAGIC_V2,
            streamId = streamId,
            frameType = FRAME_FINAL,
            sequence = sequence,
            plainLength = 0,
            totalPlainBytes = totalPlainBytes
        ))
        val finalTag = finalCipher.doFinal()

        output.write(ByteBuffer.allocate(4).putInt(0).array())
        output.write(ByteBuffer.allocate(8).putLong(sequence).array())
        output.write(finalIv)
        output.write(finalTag)
        output.flush()
    }

    @Throws(SecurityException::class)
    fun decryptStream(input: InputStream, output: OutputStream) {
        assertAuthorized()

        val header = ByteArray(MAGIC_V2.size)
        if (readFully(input, header) != header.size) {
            throw SecurityException("Corrupted vault stream: truncated header")
        }

        when {
            header.contentEquals(MAGIC_V2) -> decryptV2Stream(input, output)
            header.contentEquals(MAGIC_V1) -> decryptV1Stream(input, output)
            else -> decryptLegacyStream(header, input, output)
        }
    }

    private fun decryptV2Stream(input: InputStream, output: OutputStream) {
        val secretKey = activeSecretKey()
        val streamId = ByteArray(STREAM_ID_LENGTH)
        if (readFully(input, streamId) != streamId.size) {
            throw SecurityException("Corrupted SFV2 stream: truncated stream id")
        }

        val lenBytes = ByteArray(4)
        val seqBytes = ByteArray(8)
        var expectedSequence = 0L
        var totalPlainBytes = 0L

        while (true) {
            if (readFully(input, lenBytes) != lenBytes.size) {
                throw SecurityException("Corrupted SFV2 stream: missing authenticated terminal frame")
            }
            if (readFully(input, seqBytes) != seqBytes.size) {
                throw SecurityException("Corrupted SFV2 stream: truncated sequence")
            }

            val plainLength = ByteBuffer.wrap(lenBytes).int
            val sequence = ByteBuffer.wrap(seqBytes).long
            if (sequence != expectedSequence) {
                throw SecurityException("Corrupted SFV2 stream: unexpected sequence $sequence, expected $expectedSequence")
            }
            if (plainLength < 0 || plainLength > CHUNK_SIZE) {
                throw SecurityException("Corrupted SFV2 stream: invalid chunk length $plainLength")
            }

            val iv = ByteArray(GCM_IV_LENGTH)
            if (readFully(input, iv) != iv.size) {
                throw SecurityException("Corrupted SFV2 stream: truncated IV")
            }

            if (plainLength == 0) {
                val finalTag = ByteArray(GCM_TAG_BYTES)
                if (readFully(input, finalTag) != finalTag.size) {
                    throw SecurityException("Corrupted SFV2 stream: truncated terminal authentication tag")
                }

                try {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                    cipher.updateAAD(buildFrameAad(
                        magic = MAGIC_V2,
                        streamId = streamId,
                        frameType = FRAME_FINAL,
                        sequence = sequence,
                        plainLength = 0,
                        totalPlainBytes = totalPlainBytes
                    ))
                    val terminalPlaintext = cipher.doFinal(finalTag)
                    if (terminalPlaintext.isNotEmpty()) {
                        throw SecurityException("Corrupted SFV2 stream: terminal frame produced plaintext")
                    }
                } catch (e: AEADBadTagException) {
                    throw SecurityException("SFV2 terminal authentication failed", e)
                } catch (e: SecurityException) {
                    throw e
                } catch (e: Exception) {
                    throw SecurityException("SFV2 terminal verification failed", e)
                }

                if (input.read() != -1) {
                    throw SecurityException("Corrupted SFV2 stream: trailing bytes after terminal frame")
                }
                output.flush()
                return
            }

            val cipherBuffer = ByteArray(plainLength + GCM_TAG_BYTES)
            if (readFully(input, cipherBuffer) != cipherBuffer.size) {
                throw SecurityException("Corrupted SFV2 stream: truncated ciphertext or authentication tag")
            }

            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                cipher.updateAAD(buildFrameAad(
                    magic = MAGIC_V2,
                    streamId = streamId,
                    frameType = FRAME_DATA,
                    sequence = sequence,
                    plainLength = plainLength,
                    totalPlainBytes = totalPlainBytes
                ))
                val plainChunk = cipher.doFinal(cipherBuffer)
                if (plainChunk.size != plainLength) {
                    throw SecurityException("Corrupted SFV2 stream: plaintext length mismatch")
                }
                output.write(plainChunk)
            } catch (e: AEADBadTagException) {
                throw SecurityException("SFV2 authentication failed: file has been tampered or corrupted", e)
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                throw SecurityException("SFV2 cryptographic verification failure", e)
            }

            totalPlainBytes += plainLength
            expectedSequence++
        }
    }

    private fun decryptV1Stream(input: InputStream, output: OutputStream) {
        val secretKey = activeSecretKey()
        val lenBytes = ByteArray(4)

        while (true) {
            if (readFully(input, lenBytes) != lenBytes.size) {
                throw SecurityException("Corrupted SFV1 stream: truncated chunk length header")
            }
            val plainLength = ByteBuffer.wrap(lenBytes).int
            if (plainLength == 0) {
                output.flush()
                return
            }
            if (plainLength < 0 || plainLength > CHUNK_SIZE) {
                throw SecurityException("Corrupted SFV1 stream: invalid chunk length $plainLength")
            }

            val iv = ByteArray(GCM_IV_LENGTH)
            if (readFully(input, iv) != iv.size) {
                throw SecurityException("Corrupted SFV1 stream: truncated IV")
            }

            val cipherBuffer = ByteArray(plainLength + GCM_TAG_BYTES)
            if (readFully(input, cipherBuffer) != cipherBuffer.size) {
                throw SecurityException("Corrupted SFV1 stream: truncated ciphertext or authentication tag")
            }

            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                val plainChunk = cipher.doFinal(cipherBuffer)
                output.write(plainChunk)
            } catch (e: AEADBadTagException) {
                throw SecurityException("SFV1 authentication failed: file has been tampered or corrupted", e)
            } catch (e: Exception) {
                throw SecurityException("SFV1 cryptographic verification failure", e)
            }
        }
    }

    @Throws(SecurityException::class)
    fun verifyStreamIntegrity(input: InputStream) {
        assertAuthorized()
        val nullOutput = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
        decryptStream(input, nullOutput)
    }

    fun decryptToByteArray(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        decryptStream(input, out)
        return out.toByteArray()
    }

    private fun buildFrameAad(
        magic: ByteArray,
        streamId: ByteArray,
        frameType: Byte,
        sequence: Long,
        plainLength: Int,
        totalPlainBytes: Long
    ): ByteArray {
        return ByteBuffer.allocate(
            magic.size + streamId.size + 1 + java.lang.Long.BYTES + Integer.BYTES + java.lang.Long.BYTES
        )
            .put(magic)
            .put(streamId)
            .put(frameType)
            .putLong(sequence)
            .putInt(plainLength)
            .putLong(totalPlainBytes)
            .array()
    }

    private fun decryptLegacyStream(header: ByteArray, input: InputStream, output: OutputStream) {
        val secretKey = activeSecretKey()
        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(header, 0, iv, 0, minOf(header.size, GCM_IV_LENGTH))
        val remainingIv = GCM_IV_LENGTH - header.size
        if (remainingIv > 0 && readFully(input, iv, header.size, remainingIv) != remainingIv) {
            throw SecurityException("Corrupted legacy vault stream: truncated IV")
        }

        val allRemaining = input.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decrypted = cipher.doFinal(allRemaining)
        output.write(decrypted)
        output.flush()
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset
    ): Int {
        var total = 0
        while (total < length) {
            val count = input.read(buffer, offset + total, length - total)
            if (count == -1) break
            total += count
        }
        return total
    }
}
