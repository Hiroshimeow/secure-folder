package com.example

import android.app.Application
import com.example.admin.ProvisioningManager
import com.example.core.crypto.KeyStoreManager
import com.example.core.crypto.VaultCipher
import com.example.core.profile.CrossProfileBridge
import com.example.core.profile.ProfileManager
import com.example.data.repository.AppRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.VaultRepository

class SecureFolderApp : Application() {

    companion object {
        lateinit var instance: SecureFolderApp
            private set
    }

    lateinit var keyStoreManager: KeyStoreManager
        private set
    lateinit var vaultCipher: VaultCipher
        private set
    lateinit var provisioningManager: ProvisioningManager
        private set
    lateinit var profileManager: ProfileManager
        private set
    lateinit var crossProfileBridge: CrossProfileBridge
        private set
    lateinit var sessionManager: com.example.core.session.SessionManager
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var vaultRepository: VaultRepository
        private set
    lateinit var appRepository: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        keyStoreManager = KeyStoreManager()
        vaultCipher = VaultCipher(keyStoreManager)
        provisioningManager = ProvisioningManager(this)
        profileManager = ProfileManager(this, provisioningManager)
        crossProfileBridge = CrossProfileBridge(this, provisioningManager)
        sessionManager = com.example.core.session.SessionManager()

        settingsRepository = SettingsRepository(this)
        vaultRepository = VaultRepository(this, vaultCipher)
        appRepository = AppRepository(this, profileManager, provisioningManager)
    }
}
