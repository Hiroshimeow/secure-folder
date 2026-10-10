package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.camera.SecureCameraScreen
import com.example.ui.gallery.VaultGalleryScreen
import com.example.ui.home.FolderHomeScreen
import com.example.ui.lock.LockScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.SecureFolderTheme
import com.example.viewmodel.FolderHomeViewModel
import com.example.viewmodel.LockViewModel
import com.example.viewmodel.VaultViewModel

sealed class Screen {
    data object Home : Screen()
    data class Vault(val filterType: String? = null) : Screen()
    data object Camera : Screen()
    data object Settings : Screen()
}

class MainActivity : FragmentActivity() {

    private lateinit var lockViewModel: LockViewModel
    private var lastBackgroundTimestamp: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // P1.06: Enforce FLAG_SECURE to prevent screenshots, screen recording, and task switcher leaks
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()

        setContent {
            SecureFolderTheme(darkTheme = true) {
                lockViewModel = viewModel()
                val homeViewModel: FolderHomeViewModel = viewModel()
                val vaultViewModel: VaultViewModel = viewModel()

                val lockState by lockViewModel.uiState.collectAsState()
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                Crossfade(
                    targetState = lockState.isLocked,
                    animationSpec = tween(durationMillis = 300),
                    label = "LockTransition"
                ) { isLocked ->
                    if (isLocked) {
                        LockScreen(
                            viewModel = lockViewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        when (val screen = currentScreen) {
                            is Screen.Home -> {
                                FolderHomeScreen(
                                    viewModel = homeViewModel,
                                    onOpenVault = { filter ->
                                        currentScreen = Screen.Vault(filter)
                                    },
                                    onOpenCamera = {
                                        currentScreen = Screen.Camera
                                    },
                                    onOpenSettings = {
                                        currentScreen = Screen.Settings
                                    },
                                    onLockFolder = {
                                        lockViewModel.lockVault()
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            is Screen.Vault -> {
                                VaultGalleryScreen(
                                    viewModel = vaultViewModel,
                                    initialFilter = screen.filterType,
                                    onBack = { currentScreen = Screen.Home },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            is Screen.Camera -> {
                                SecureCameraScreen(
                                    onBack = { currentScreen = Screen.Home },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            is Screen.Settings -> {
                                SettingsScreen(
                                    onBack = { currentScreen = Screen.Home },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // P1.04: Check timeout auto-lock policy when returning to foreground
        val settings = SecureFolderApp.instance.settingsRepository.settings.value
        if (settings.autoLockTimeoutMinutes > 0 && lastBackgroundTimestamp > 0L) {
            val elapsedMillis = System.currentTimeMillis() - lastBackgroundTimestamp
            val thresholdMillis = settings.autoLockTimeoutMinutes * 60 * 1000L
            if (elapsedMillis >= thresholdMillis) {
                if (::lockViewModel.isInitialized) {
                    lockViewModel.lockVault()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        lastBackgroundTimestamp = System.currentTimeMillis()
        // P1.04: If auto-lock is immediate (0 mins), lock immediately upon leaving
        val settings = SecureFolderApp.instance.settingsRepository.settings.value
        if (settings.autoLockTimeoutMinutes == 0) {
            if (::lockViewModel.isInitialized) {
                lockViewModel.lockVault()
            }
        }
    }
}
