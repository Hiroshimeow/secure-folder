package com.example.data.repository

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import com.example.core.crypto.VaultCipher
import com.example.data.model.VaultItemType
import com.example.data.model.VaultMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID

enum class ImportMode {
    COPY, // Keep source file intact on device
    MOVE  // Securely delete source file after destination verification
}

data class SafeImportResult(
    val item: VaultMediaItem,
    val mode: ImportMode,
    val sourceDeleted: Boolean,
    val pendingDeleteIntentSender: IntentSender? = null,
    val originalUri: Uri? = null
)

/**
 * Isolated Storage Engine and AES-256 Vault Repository.
 * All files are stored with AES-256-GCM authentication in isolated internal storage.
 * Enforces strict Safe Copy & Safe Move semantics with destination integrity verification.
 * 
 * Production Hardening:
 * 1. Atomic Index Commit: Uses Android's AtomicFile to guarantee index durability on crash/reboot.
 * 2. 100% Cryptographic Wipe: Overwrites all file bytes with random data, flushes, syncs, and deletes.
 * 3. Streaming AEAD verification: Verifies full stream integrity without buffering in RAM.
 * 4. Concurrency control via Mutex.
 */
class VaultRepository(
    private val context: Context,
    private val vaultCipher: VaultCipher
) {

    companion object {
        private const val TAG = "VaultRepository"
        private const val VAULT_DIR = "secure_vault"
        private const val INDEX_FILE = "vault_index.enc"
    }

    private val vaultDir: File = File(context.filesDir, VAULT_DIR).apply {
        if (!exists()) mkdirs()
    }

    private val atomicIndexFile = AtomicFile(File(vaultDir, INDEX_FILE))
    private val vaultMutex = Mutex()

    private val _vaultItems = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val vaultItems: StateFlow<List<VaultMediaItem>> = _vaultItems.asStateFlow()

    fun clearVaultCacheOnLock() {
        _vaultItems.value = emptyList()
    }

    @Synchronized
    fun loadVaultIndex() {
        try {
            if (!atomicIndexFile.baseFile.exists()) {
                _vaultItems.value = emptyList()
                return
            }
            val encryptedBytes = atomicIndexFile.readFully()
            if (encryptedBytes.isEmpty()) {
                _vaultItems.value = emptyList()
                return
            }
            val decryptedBytes = vaultCipher.decrypt(encryptedBytes)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<VaultMediaItem>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    VaultMediaItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        fileName = obj.getString("fileName"),
                        itemType = VaultItemType.valueOf(obj.getString("itemType")),
                        fileSize = obj.getLong("fileSize"),
                        timestamp = obj.getLong("timestamp"),
                        mimeType = obj.getString("mimeType"),
                        noteContent = if (obj.has("noteContent")) obj.optString("noteContent") else null
                    )
                )
            }
            _vaultItems.value = list.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading vault index", e)
            _vaultItems.value = emptyList()
        }
    }

    /**
     * Atomic index commit using AtomicFile.
     * Prevents data loss during power cuts or unexpected crashes.
     */
    @Throws(IOException::class)
    private fun saveVaultIndex() {
        val jsonArray = JSONArray()
        for (item in _vaultItems.value) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("fileName", item.fileName)
                put("itemType", item.itemType.name)
                put("fileSize", item.fileSize)
                put("timestamp", item.timestamp)
                put("mimeType", item.mimeType)
                if (item.noteContent != null) {
                    put("noteContent", item.noteContent)
                }
            }
            jsonArray.put(obj)
        }
        val plainBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
        val encryptedBytes = vaultCipher.encrypt(plainBytes)

        var fos: FileOutputStream? = null
        try {
            fos = atomicIndexFile.startWrite()
            fos.write(encryptedBytes)
            atomicIndexFile.finishWrite(fos)
        } catch (e: Exception) {
            if (fos != null) {
                atomicIndexFile.failWrite(fos)
            }
            throw IOException("Atomic index commit failed", e)
        }
    }

    /**
     * Safe Import Pipeline:
     * 1. Read input stream from source URI with resource auto-close.
     * 2. Encrypt stream directly into isolated disk file (<id>.enc) via Chunked AEAD.
     * 3. Perform destination readback integrity verification via streaming (Zero RAM bloat).
     * 4. Atomic commit to index.
     * 5. If mode is MOVE:
     *    - Request OS consent via MediaStore.createDeleteRequest (Android 11+)
     *    - Or delete via DocumentsContract / ContentResolver.
     *    - If user cancels or permission denied: retain encrypted item and return sourceDeleted = false.
     */
    suspend fun importFileSafely(
        uri: Uri,
        originalName: String,
        mimeType: String,
        mode: ImportMode
    ): Result<SafeImportResult> = withContext(Dispatchers.IO) {
        vaultMutex.withLock {
            try {
                val id = UUID.randomUUID().toString()
                val encFileName = "$id.enc"
                val targetFile = File(vaultDir, encFileName)

                val openStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Cannot open stream for URI $uri"))

                openStream.use { inputStream ->
                    FileOutputStream(targetFile).use { outStream ->
                        vaultCipher.encryptStream(inputStream, outStream)
                    }
                }

                // Streaming Readback verification: verify AEAD tags without loading into RAM
                try {
                    FileInputStream(targetFile).use { inStream ->
                        vaultCipher.verifyStreamIntegrity(inStream)
                    }
                } catch (e: Exception) {
                    targetFile.delete()
                    return@withContext Result.failure(Exception("Readback cryptographic verification failed", e))
                }

                val itemType = when {
                    mimeType.startsWith("image/") -> VaultItemType.IMAGE
                    mimeType.startsWith("video/") -> VaultItemType.VIDEO
                    else -> VaultItemType.DOCUMENT
                }

                val item = VaultMediaItem(
                    id = id,
                    title = originalName,
                    fileName = encFileName,
                    itemType = itemType,
                    fileSize = targetFile.length(),
                    timestamp = System.currentTimeMillis(),
                    mimeType = mimeType
                )

                // Atomic commit to vault index
                val updated = _vaultItems.value.toMutableList()
                updated.add(0, item)
                _vaultItems.value = updated
                saveVaultIndex()

                var sourceDeleted = false
                var pendingIntentSender: IntentSender? = null

                if (mode == ImportMode.MOVE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.authority == MediaStore.AUTHORITY) {
                        try {
                            val uris = listOf(uri)
                            val deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, uris)
                            pendingIntentSender = deleteRequest.intentSender
                        } catch (e: Exception) {
                            Log.w(TAG, "MediaStore createDeleteRequest failed: ${e.message}")
                            try {
                                val count = context.contentResolver.delete(uri, null, null)
                                sourceDeleted = count > 0
                            } catch (sec: Exception) {
                                Log.w(TAG, "Direct delete on MediaStore denied: ${sec.message}")
                            }
                        }
                    } else {
                        try {
                            if (DocumentsContract.isDocumentUri(context, uri)) {
                                sourceDeleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
                            } else {
                                val count = context.contentResolver.delete(uri, null, null)
                                sourceDeleted = count > 0
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete original file at $uri: ${e.message}")
                            sourceDeleted = false
                        }
                    }
                }

                Result.success(
                    SafeImportResult(
                        item = item,
                        mode = mode,
                        sourceDeleted = sourceDeleted,
                        pendingDeleteIntentSender = pendingIntentSender,
                        originalUri = uri
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error importing file safely", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Ingests bytes directly from Secure Camera into hardware-encrypted storage.
     */
    suspend fun saveEncryptedPhoto(bytes: ByteArray): Result<VaultMediaItem> {
        return ingestCameraBytes(
            bytes = bytes,
            title = "Ảnh chụp an toàn ${System.currentTimeMillis()}",
            mimeType = "image/jpeg"
        )
    }

    suspend fun ingestCameraBytes(
        bytes: ByteArray,
        title: String,
        mimeType: String
    ): Result<VaultMediaItem> = withContext(Dispatchers.IO) {
        vaultMutex.withLock {
            try {
                val id = UUID.randomUUID().toString()
                val encFileName = "CAM_$id.enc"
                val targetFile = File(vaultDir, encFileName)

                val encrypted = vaultCipher.encrypt(bytes)
                targetFile.writeBytes(encrypted)

                val item = VaultMediaItem(
                    id = id,
                    title = title,
                    fileName = encFileName,
                    itemType = if (mimeType.startsWith("video/")) VaultItemType.VIDEO else VaultItemType.IMAGE,
                    fileSize = targetFile.length(),
                    timestamp = System.currentTimeMillis(),
                    mimeType = mimeType
                )

                val updated = _vaultItems.value.toMutableList()
                updated.add(0, item)
                _vaultItems.value = updated
                saveVaultIndex()

                Result.success(item)
            } catch (e: Exception) {
                Log.e(TAG, "Error ingesting camera bytes", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Saves or updates a secure text note with atomic file write and integrity check.
     */
    suspend fun saveSecureNoteAtomic(
        existingId: String?,
        title: String,
        content: String
    ): Result<VaultMediaItem> = withContext(Dispatchers.IO) {
        vaultMutex.withLock {
            try {
                val id = existingId ?: UUID.randomUUID().toString()
                val encFileName = "NOTE_$id.enc"
                val tempFile = File(vaultDir, "$encFileName.tmp")

                val encryptedBytes = vaultCipher.encrypt(content.toByteArray(Charsets.UTF_8))
                tempFile.writeBytes(encryptedBytes)

                val testRead = vaultCipher.decrypt(tempFile.readBytes())
                if (String(testRead, Charsets.UTF_8) != content) {
                    tempFile.delete()
                    return@withContext Result.failure(IllegalStateException("Note verification mismatch"))
                }

                val targetFile = File(vaultDir, encFileName)
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.delete()
                    return@withContext Result.failure(IOException("Failed to atomically rename note temp file to target"))
                }

                val item = VaultMediaItem(
                    id = id,
                    title = title.ifBlank { "Ghi chú không tên" },
                    fileName = encFileName,
                    itemType = VaultItemType.NOTE,
                    fileSize = targetFile.length(),
                    timestamp = System.currentTimeMillis(),
                    mimeType = "text/plain",
                    noteContent = content
                )

                val updated = _vaultItems.value.filter { it.id != id }.toMutableList()
                updated.add(0, item)
                _vaultItems.value = updated
                saveVaultIndex()

                Result.success(item)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save secure note atomically", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Decrypts an encrypted file in memory for display in secure viewer.
     */
    suspend fun decryptItemBytes(item: VaultMediaItem): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val file = File(vaultDir, item.fileName)
            if (!file.exists()) return@withContext null
            FileInputStream(file).use { input ->
                vaultCipher.decryptToByteArray(input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt item ${item.id}", e)
            null
        }
    }

    /**
     * Decrypts note content.
     */
    suspend fun readNoteContent(item: VaultMediaItem): String? = withContext(Dispatchers.IO) {
        if (item.noteContent != null) return@withContext item.noteContent
        try {
            val bytes = decryptItemBytes(item) ?: return@withContext null
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Secure Wipe:
     * Overwrites 100% of the entire file length with cryptographic random bytes before deletion.
     */
    suspend fun securelyDeleteItem(item: VaultMediaItem): Boolean = withContext(Dispatchers.IO) {
        vaultMutex.withLock {
            try {
                val file = File(vaultDir, item.fileName)
                if (file.exists()) {
                    val totalLength = file.length()
                    if (totalLength > 0) {
                        val buffer = ByteArray(64 * 1024)
                        val random = SecureRandom()
                        var remaining = totalLength
                        FileOutputStream(file).use { out ->
                            while (remaining > 0) {
                                val toWrite = minOf(remaining, buffer.size.toLong()).toInt()
                                random.nextBytes(buffer)
                                out.write(buffer, 0, toWrite)
                                remaining -= toWrite
                            }
                            out.flush()
                            out.fd.sync()
                        }
                    }
                    val deleted = file.delete()
                    if (!deleted && file.exists()) {
                        Log.w(TAG, "File delete returned false: ${file.name}")
                    }
                }
                val updated = _vaultItems.value.filter { it.id != item.id }
                _vaultItems.value = updated
                saveVaultIndex()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to securely wipe item ${item.id}", e)
                false
            }
        }
    }

    /**
     * Complete Vault Data Wipe:
     * Overwrites 100% of all bytes of every file in the vault with random data, syncs, and deletes.
     */
    suspend fun wipeAllVaultData(): Boolean = withContext(Dispatchers.IO) {
        vaultMutex.withLock {
            try {
                val files = vaultDir.listFiles() ?: emptyArray()
                val random = SecureRandom()
                val buffer = ByteArray(64 * 1024)

                for (file in files) {
                    if (file.isFile) {
                        try {
                            val total = file.length()
                            if (total > 0) {
                                var remaining = total
                                FileOutputStream(file).use { out ->
                                    while (remaining > 0) {
                                        val toWrite = minOf(remaining, buffer.size.toLong()).toInt()
                                        random.nextBytes(buffer)
                                        out.write(buffer, 0, toWrite)
                                        remaining -= toWrite
                                    }
                                    out.flush()
                                    out.fd.sync()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error wiping content of ${file.name}: ${e.message}")
                        }
                        file.delete()
                    }
                }

                atomicIndexFile.delete()
                _vaultItems.value = emptyList()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during complete vault wipe", e)
                false
            }
        }
    }
}
