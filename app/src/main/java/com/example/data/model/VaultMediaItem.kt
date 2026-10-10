package com.example.data.model

enum class VaultItemType {
    IMAGE,
    VIDEO,
    NOTE,
    DOCUMENT
}

/**
 * Model representing an encrypted item stored inside Secure Folder hardware-backed vault.
 */
data class VaultMediaItem(
    val id: String,
    val title: String,
    val fileName: String,
    val itemType: VaultItemType,
    val fileSize: Long,
    val timestamp: Long,
    val mimeType: String,
    val noteContent: String? = null
)
