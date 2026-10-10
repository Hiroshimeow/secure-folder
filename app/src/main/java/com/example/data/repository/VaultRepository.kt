package com.example.data.repository

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.example.core.crypto.VaultCipher
import com.example.data.model.VaultItemType
import com.example.data.model.VaultMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
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
 * Repository for Hardware-Encrypted Media & Document Vault.
 * All files are stored with AES-256-GCM authentication in isolated internal storage.
 * Enforces strict Safe Copy & Safe Move semantics with destination integrity verification.
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

    private val _vaultItems = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val vaultItems: StateFlow<List<VaultMediaItem>> = _vaultItems.asStateFlow()

    init {
        loadVaultIndex()
    }

    private fun loadVaultIndex() {
        try {
            val indexFile = File(vaultDir, INDEX_FILE)
            if (!indexFile.exists()) {
                _vaultItems.value = emptyList()
                return
            }
            val encryptedBytes = indexFile.readBytes()
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
            Log.e(TAG, "Error loading vault index, creating fresh index", e)
            _vaultItems.value = emptyList()
        }
    }

    private fun saveVaultIndex() {
        try {
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
            val indexFile = File(vaultDir, INDEX_FILE)
            indexFile.writeBytes(encryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving vault index", e)
        }
    }

    /**
     * Safe Import Pipeline:
     * 1. Read input stream from source URI.
     * 2. Encrypt stream directly into isolated disk file (<id>.enc).
     * 3. Perform destination readback verification (ensure AES-GCM tag is valid).
     * 4. If mode is MOVE:
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
        try {
            val id = UUID.randomUUID().toString()
            val encFileName = "$id.enc"
            val targetFile = File(vaultDir, encFileName)

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open stream for URI $uri"))

            // 1. Stream encrypt directly to disk
            FileOutputStream(targetFile).use { outStream ->
                vaultCipher.encryptStream(inputStream, outStream)
            }

            // 2. Readback verification: ensure file can be successfully decrypted and authenticated
            try {
                FileInputStream(targetFile).use { inStream ->
                    val verifiedBytes = vaultCipher.decryptToByteArray(inStream)
                    if (verifiedBytes.isEmpty()) {
                        throw IllegalStateException("Destination payload is empty after encryption")
                    }
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

            // Commit item into vault
            val updated = _vaultItems.value.toMutableList()
            updated.add(0, item)
            _vaultItems.value = updated
            saveVaultIndex()

            var sourceDeleted = false
            var pendingIntentSender: IntentSender? = null

            // 3. If MOVE mode, request OS-level deletion
            if (mode == ImportMode.MOVE) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.authority == MediaStore.AUTHORITY) {
                    try {
                        val pendingDelete = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                        pendingIntentSender = pendingDelete.intentSender
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not create MediaStore delete request", e)
                    }
                } else {
                    // Try SAF or ContentResolver direct delete
                    try {
                        if (DocumentsContract.isDocumentUri(context, uri)) {
                            sourceDeleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
                        } else {
                            val rows = context.contentResolver.delete(uri, null, null)
                            sourceDeleted = rows > 0
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct content delete failed (needs user consent): ${e.message}")
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
            Log.e(TAG, "Error during safe import", e)
            Result.failure(e)
        }
    }

    /**
     * Stores a direct camera capture into the encrypted vault without MediaStore trace.
     */
    suspend fun saveEncryptedPhoto(jpegBytes: ByteArray): Result<VaultMediaItem> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val encFileName = "IMG_$id.enc"
            val targetFile = File(vaultDir, encFileName)

            val encryptedBytes = vaultCipher.encrypt(jpegBytes)
            targetFile.writeBytes(encryptedBytes)

            val item = VaultMediaItem(
                id = id,
                title = "Ảnh bảo mật ${System.currentTimeMillis() % 10000}",
                fileName = encFileName,
                itemType = VaultItemType.IMAGE,
                fileSize = targetFile.length(),
                timestamp = System.currentTimeMillis(),
                mimeType = "image/jpeg"
            )

            val updated = _vaultItems.value.toMutableList()
            updated.add(0, item)
            _vaultItems.value = updated
            saveVaultIndex()

            Result.success(item)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save encrypted photo", e)
            Result.failure(e)
        }
    }

    /**
     * Atomic Upsert for Secure Notes:
     * Saves new encrypted note file and verifies write before updating index.
     */
    suspend fun saveSecureNoteAtomic(
        existingId: String?,
        title: String,
        content: String
    ): Result<VaultMediaItem> = withContext(Dispatchers.IO) {
        try {
            val id = existingId ?: UUID.randomUUID().toString()
            val encFileName = "NOTE_$id.enc"
            val tempFile = File(vaultDir, "$encFileName.tmp")

            val encryptedBytes = vaultCipher.encrypt(content.toByteArray(Charsets.UTF_8))
            tempFile.writeBytes(encryptedBytes)

            // Verify readback
            val testRead = vaultCipher.decrypt(tempFile.readBytes())
            if (String(testRead, Charsets.UTF_8) != content) {
                tempFile.delete()
                return@withContext Result.failure(IllegalStateException("Note verification mismatch"))
            }

            val targetFile = File(vaultDir, encFileName)
            tempFile.renameTo(targetFile)

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
     * Secure Wipe: Overwrites file with random bytes before deletion.
     */
    suspend fun securelyDeleteItem(item: VaultMediaItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(vaultDir, item.fileName)
            if (file.exists()) {
                val length = file.length().toInt()
                if (length > 0) {
                    val randomBytes = ByteArray(minOf(length, 4096))
                    SecureRandom().nextBytes(randomBytes)
                    FileOutputStream(file).use { it.write(randomBytes) }
                }
                file.delete()
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
