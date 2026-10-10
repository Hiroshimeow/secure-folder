package com.example.data.model

import android.graphics.drawable.Drawable

/**
 * Model representing an application cloned into or available for Secure Folder.
 */
data class ClonedApp(
    val packageName: String,
    val appLabel: String,
    val componentClassName: String,
    val isWorkProfile: Boolean,
    val isSystemApp: Boolean = false,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val icon: Drawable? = null
)
