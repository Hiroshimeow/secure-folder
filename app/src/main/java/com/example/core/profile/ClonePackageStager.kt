package com.example.core.profile

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates short-lived, URI-granted copies of all APK files (base + splits) for a Personal-profile
 * app so the managed-profile executor can stream them into PackageInstaller.
 */
class ClonePackageStager(private val context: Context) {

    companion object {
        private const val ROOT_DIR = "clone_apks"
        private const val AUTHORITY_SUFFIX = ".clonefiles"
    }

    suspend fun stage(packageName: String): ArrayList<Uri> = withContext(Dispatchers.IO) {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        val sourcePaths = buildList {
            add(appInfo.sourceDir)
            appInfo.splitSourceDirs?.let(::addAll)
        }

        require(sourcePaths.isNotEmpty()) { "No APK files found for $packageName" }

        val packageDir = packageDirectory(packageName)
        packageDir.deleteRecursively()
        check(packageDir.mkdirs() || packageDir.isDirectory) {
            "Cannot create staging directory for $packageName"
        }

        val stagedUris = ArrayList<Uri>(sourcePaths.size)
        try {
            sourcePaths.forEachIndexed { index, sourcePath ->
                val source = File(sourcePath)
                check(source.isFile && source.canRead()) {
                    "APK source is not readable: $sourcePath"
                }

                val destinationName = if (index == 0) {
                    "base.apk"
                } else {
                    source.name.takeIf { it.endsWith(".apk", ignoreCase = true) }
                        ?: "split_$index.apk"
                }
                val destination = File(packageDir, destinationName)

                source.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 128 * 1024)
                    }
                }

                check(destination.length() == source.length()) {
                    "Staged APK size mismatch for $destinationName"
                }

                val providerUri = FileProvider.getUriForFile(
                    context,
                    context.packageName + AUTHORITY_SUFFIX,
                    destination
                )
                // Keep the provider URI in public-API form. Cross-profile intent forwarding
                // carries the source-user URI grant via ClipData + FLAG_GRANT_READ_URI_PERMISSION.
                stagedUris += providerUri
            }

            stagedUris
        } catch (e: Exception) {
            packageDir.deleteRecursively()
            throw e
        }
    }

    fun cleanup(packageName: String) {
        packageDirectory(packageName).deleteRecursively()
    }

    private fun packageDirectory(packageName: String): File {
        val safeName = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(context.cacheDir, ROOT_DIR), safeName)
    }
}
