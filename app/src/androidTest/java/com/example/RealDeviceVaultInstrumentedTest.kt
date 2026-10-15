package com.example

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.session.VaultSessionMode
import com.example.data.repository.ImportMode
import com.example.data.repository.SettingsRepository
import com.example.data.repository.VaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealDeviceVaultInstrumentedTest {

    private lateinit var app: SecureFolderApp
    private lateinit var repository: VaultRepository
    private lateinit var isolatedContext: Context
    private lateinit var sandboxRoot: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()

        sandboxRoot = File(app.cacheDir, "instrumentation-vault-sandbox").apply {
            deleteRecursively()
            check(mkdirs() || isDirectory)
        }

        val isolatedFiles = File(sandboxRoot, "files").apply {
            check(mkdirs() || isDirectory)
        }
        val isolatedCache = File(sandboxRoot, "cache").apply {
            check(mkdirs() || isDirectory)
        }

        isolatedContext = object : ContextWrapper(app) {
            override fun getFilesDir(): File = isolatedFiles
            override fun getCacheDir(): File = isolatedCache
        }

        app.sessionManager.startSession()
        repository = VaultRepository(isolatedContext, app.vaultCipher)
    }

    @After
    fun tearDown() {
        app.sessionManager.invalidateSession()
        sandboxRoot.deleteRecursively()
    }

    @Test
    fun androidKeystore_sfv2RoundTrip_importAndNoteSave() = runBlocking {
        val plain = ByteArray(180_000) { index -> (index % 251).toByte() }

        val encrypted = app.vaultCipher.encrypt(plain)
        assertTrue(
            "New writes must use SFV2",
            encrypted.copyOfRange(0, 4).contentEquals("SFV2".toByteArray())
        )
        assertArrayEquals(plain, app.vaultCipher.decrypt(encrypted))

        val source = File(isolatedContext.cacheDir, "vault-import-source.bin")
        source.writeBytes(plain)

        val importResult = repository.importFileSafely(
            uri = Uri.fromFile(source),
            originalName = "vault-import-source.bin",
            mimeType = "application/octet-stream",
            mode = ImportMode.COPY
        )
        assertTrue(
            "Real-device import must succeed: ${importResult.exceptionOrNull()}",
            importResult.isSuccess
        )

        val imported = importResult.getOrThrow().item
        assertArrayEquals(
            "Imported payload must decrypt byte-for-byte",
            plain,
            repository.decryptItemBytes(imported)
        )

        val noteText = "AndroidKeyStore real-device note ${System.nanoTime()}"
        val noteResult = repository.saveSecureNoteAtomic(
            existingId = null,
            title = "Instrumentation Note",
            content = noteText
        )
        assertTrue(
            "Real-device note save must succeed: ${noteResult.exceptionOrNull()}",
            noteResult.isSuccess
        )

        val note = noteResult.getOrThrow()
        val decryptedNote = repository.decryptItemBytes(note)
        assertEquals(noteText, decryptedNote?.toString(Charsets.UTF_8))
    }

    @Test
    fun realAndroid_primaryAndDecoyPinRolesAreDistinct() = runBlocking {
        // Use the androidTest APK's own SharedPreferences so this never mutates production PINs.
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val settings = SettingsRepository(testContext)
        settings.resetAllSettings()

        assertTrue(settings.setPin("0000"))
        assertTrue(settings.setDecoyPin("1111"))
        assertEquals(VaultSessionMode.PRIMARY, settings.verifyPinRole("0000"))
        assertEquals(VaultSessionMode.DECOY, settings.verifyPinRole("1111"))
        assertEquals(null, settings.verifyPinRole("2222"))

        settings.resetAllSettings()
    }

    @Test
    fun androidKeystore_primaryAndDecoyRemainCryptographicallyIsolated() = runBlocking {
        repository.wipeAllVaultNamespaces()

        app.sessionManager.invalidateSession()
        app.sessionManager.startSession(VaultSessionMode.PRIMARY)
        val primaryCiphertext = app.vaultCipher.encrypt("physical-primary-secret".toByteArray())
        assertTrue(
            repository.saveSecureNoteAtomic(null, "Primary Device Note", "primary").isSuccess
        )
        repository.loadVaultIndex()
        assertEquals(listOf("Primary Device Note"), repository.vaultItems.value.map { it.title })

        app.sessionManager.invalidateSession()
        app.sessionManager.startSession(VaultSessionMode.DECOY)
        var crossDecryptRejected = false
        try {
            app.vaultCipher.decrypt(primaryCiphertext)
        } catch (_: SecurityException) {
            crossDecryptRejected = true
        }
        assertTrue("DECOY key must not decrypt PRIMARY ciphertext", crossDecryptRejected)

        repository.loadVaultIndex()
        assertTrue("DECOY namespace must start independently", repository.vaultItems.value.isEmpty())
        assertTrue(
            repository.saveSecureNoteAtomic(null, "Decoy Device Note", "decoy").isSuccess
        )
        repository.loadVaultIndex()
        assertEquals(listOf("Decoy Device Note"), repository.vaultItems.value.map { it.title })

        app.sessionManager.invalidateSession()
        app.sessionManager.startSession(VaultSessionMode.PRIMARY)
        repository.loadVaultIndex()
        assertEquals(listOf("Primary Device Note"), repository.vaultItems.value.map { it.title })
    }
}
