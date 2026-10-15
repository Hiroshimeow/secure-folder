package com.example

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.profile.ManagedExecutorActivity
import com.example.core.security.EmergencyMotionController
import com.example.core.session.VaultSessionMode
import com.example.ui.camera.SecureCameraScreen
import com.example.ui.gallery.VaultGalleryScreen
import com.example.ui.home.FolderHomeScreen
import com.example.ui.home.ManagedExecutorScreen
import com.example.ui.lock.LockScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.SecureFolderTheme
import com.example.viewmodel.FolderHomeViewModel
import com.example.viewmodel.LockViewModel
import com.example.viewmodel.VaultViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Screen {
    data object Home : Screen()
    data class Vault(val filterType: String? = null) : Screen()
    data object Camera : Screen()
    data object Settings : Screen()
}

/**
 * Bounded lease manager for system dialog flows (Document Picker, Photo Picker, Camera).
 * Guarantees that system round-trips do not kill the session prematurely while preventing
 * permanent unauthenticated leaks through an automatic 60-second time bound.
 */
object ExternalSystemFlowLease {
    @Volatile
    private var leaseExpiryTime: Long = 0L

    fun acquireLease(durationMillis: Long = 60_000L) {
        leaseExpiryTime = System.currentTimeMillis() + durationMillis
    }

    fun releaseLease() {
        leaseExpiryTime = 0L
    }

    val isLeaseActive: Boolean
        get() = System.currentTimeMillis() < leaseExpiryTime
}

class MainActivity : FragmentActivity() {

    companion object {
        /**
         * Backward-compatible property backed by a bounded lease.
         */
        var isLaunchingSystemIntent: Boolean
            get() = ExternalSystemFlowLease.isLeaseActive
            set(value) {
                if (value) {
                    ExternalSystemFlowLease.acquireLease(60_000L)
                } else {
                    ExternalSystemFlowLease.releaseLease()
                }
            }
    }

    private lateinit var lockViewModel: LockViewModel
    private lateinit var homeViewModel: FolderHomeViewModel
    private var emergencyMotionController: EmergencyMotionController? = null
    private var lastBackgroundTimestamp: Long = 0L
    private var backgroundLockJob: Job? = null
    private var startupLockDispatched: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enforce FLAG_SECURE to prevent screenshots, screen recording, and task switcher leaks
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()

        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        val isManagedProfile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && userManager.isManagedProfile
        val isUnsupportedSecondaryProfile =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && userManager.isProfile && !isManagedProfile

        // Component enablement is per-user. Keep the command executor unavailable in Personal/Dual-App
        // profiles so implicit cross-profile commands cannot resolve back into the caller user.
        val executorComponent = ComponentName(this, ManagedExecutorActivity::class.java)
        packageManager.setComponentEnabledSetting(
            executorComponent,
            if (isManagedProfile) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )

        // MainActivity is user-facing only in the Personal profile. Component enablement is per-user,
        // so the managed DPC remains installed and operational without exposing a second launcher icon.
        val launcherComponent = ComponentName(this, MainActivity::class.java)
        if (isManagedProfile || isUnsupportedSecondaryProfile) {
            if (isManagedProfile) {
                SecureFolderApp.instance.provisioningManager.ensureManagedProfileConfiguration()
            }
            packageManager.setComponentEnabledSetting(
                launcherComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            finish()
            return
        } else {
            packageManager.setComponentEnabledSetting(
                launcherComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            emergencyMotionController = EmergencyMotionController(this) {
                runOnUiThread {
                    if (::lockViewModel.isInitialized &&
                        !SecureFolderApp.instance.settingsRepository.settings.value.isLocked
                    ) {
                        lockViewModel.lockVault()
                    }
                }
            }
        }

        setContent {
            SecureFolderTheme(darkTheme = true) {
                if (isManagedProfile) {
                    // P1: Managed Profile is strictly an execution container.
                    // Render minimal executor UI, no Vault PIN or user data UI.
                    ManagedExecutorScreen(modifier = Modifier.fillMaxSize())
                } else {
                    // Personal Controller: Full Secure Folder Vault Experience
                    lockViewModel = viewModel()
                    val vaultViewModel: VaultViewModel = viewModel()
                    homeViewModel = viewModel()

                    val lockState by lockViewModel.uiState.collectAsState()
                    val sessionMode by SecureFolderApp.instance.sessionManager.sessionMode.collectAsState()
                    val settings by SecureFolderApp.instance.settingsRepository.settings.collectAsState()
                    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                    LaunchedEffect(lockState.isLocked, sessionMode) {
                        if (lockState.isLocked || sessionMode == VaultSessionMode.DECOY) {
                            currentScreen = Screen.Home
                        }
                    }

                    LaunchedEffect(
                        lockState.isLocked,
                        settings.shakeToHideEnabled,
                        settings.shakeSensitivity,
                        settings.faceDownToHideEnabled,
                        settings.faceDownDelayMillis
                    ) {
                        if (lockState.isLocked) {
                            emergencyMotionController?.stop()
                        } else {
                            emergencyMotionController?.updateSettings(settings)
                        }
                    }

                    Crossfade(
                        targetState = lockState.isLocked || lockState.isSecuringWorkspace,
                        animationSpec = tween(350),
                        label = "LockTransition"
                    ) { locked ->
                        if (locked) {
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
                                        isDecoyMode = sessionMode == VaultSessionMode.DECOY,
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
    }

    override fun onStart() {
        super.onStart()
        backgroundLockJob?.cancel()
        backgroundLockJob = null
    }

    override fun onResume() {
        super.onResume()
        val wasSystemIntent = ExternalSystemFlowLease.isLeaseActive
        // Release lease upon returning to foreground
        ExternalSystemFlowLease.releaseLease()

        val settings = SecureFolderApp.instance.settingsRepository.settings.value
        if (!settings.isLocked) {
            // A future explicit lock/background transition owns its own pre-dispatch.
            startupLockDispatched = false
        }
        // A restarted Personal Controller always begins LOCKED in memory. Recover the managed
        // package boundary once per Activity instance. The transient managed executor returns focus
        // to this Activity, so an unguarded onResume() would otherwise create a lock-dispatch loop.
        if (!wasSystemIntent && settings.isLocked && !startupLockDispatched) {
            startupLockDispatched = true
            SecureFolderApp.instance.profileManager.preDispatchWorkspaceLockFromForeground()
        }

        if (::homeViewModel.isInitialized) {
            homeViewModel.checkProfileStatus()
        }

        // Check timeout auto-lock policy when returning to foreground
        if (!wasSystemIntent && settings.autoLockTimeoutMinutes > 0 && lastBackgroundTimestamp > 0L) {
            val elapsedMillis = System.currentTimeMillis() - lastBackgroundTimestamp
            val thresholdMillis = settings.autoLockTimeoutMinutes * 60 * 1000L
            if (elapsedMillis >= thresholdMillis) {
                if (::lockViewModel.isInitialized) {
                    lockViewModel.lockVault()
                }
            }
        }

        if (!settings.isLocked) {
            emergencyMotionController?.updateSettings(settings)
        }
    }

    override fun onUserLeaveHint() {
        // Home / user-initiated backgrounding arrives here immediately before onPause(). Android
        // 10+ can block the Activity-based cross-profile command once this process is background,
        // so pre-dispatch the confidentiality lock while our window is still visible. System flows
        // and deliberate cloned-app launches hold a bounded lease and must not be auto-locked here.
        val settings = SecureFolderApp.instance.settingsRepository.settings.value
        if (!ExternalSystemFlowLease.isLeaseActive &&
            !settings.isLocked &&
            settings.autoLockTimeoutMinutes == 0 &&
            ::lockViewModel.isInitialized
        ) {
            SecureFolderApp.instance.profileManager.preDispatchWorkspaceLockFromForeground()
        }
        super.onUserLeaveHint()
    }

    override fun onPause() {
        emergencyMotionController?.stop()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        lastBackgroundTimestamp = System.currentTimeMillis()

        // If the Activity stopped because of a bounded system flow (e.g. system file picker or photo picker),
        // DO NOT kill the authenticated session before the callback arrives!
        if (ExternalSystemFlowLease.isLeaseActive) {
            return
        }

        val settings = SecureFolderApp.instance.settingsRepository.settings.value
        if (settings.autoLockTimeoutMinutes == 0) {
            if (::lockViewModel.isInitialized) {
                lockViewModel.lockVault()
            }
        } else if (settings.autoLockTimeoutMinutes > 0) {
            // Proactively expire session in background when timer expires
            backgroundLockJob?.cancel()
            val delayMillis = settings.autoLockTimeoutMinutes * 60 * 1000L
            backgroundLockJob = lifecycleScope.launch {
                delay(delayMillis)
                if (::lockViewModel.isInitialized) {
                    lockViewModel.lockVault()
                }
            }
        }
    }
}
