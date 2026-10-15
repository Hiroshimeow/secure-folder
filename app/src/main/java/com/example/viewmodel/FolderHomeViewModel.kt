package com.example.viewmodel

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.SecureFolderApp
import com.example.core.profile.CrossProfileDispatchResult
import com.example.core.profile.CrossProfileDispatchState
import com.example.core.profile.PackageOpState
import com.example.core.profile.PackageStatusInfo
import com.example.core.profile.ProfileRole
import com.example.core.profile.WorkspaceState
import com.example.data.model.ClonedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val clonedApps: List<ClonedApp> = emptyList(),
    val availableHostApps: List<ClonedApp> = emptyList(),
    val isAddAppSheetOpen: Boolean = false,
    val searchQuery: String = "",
    val workspaceState: WorkspaceState = WorkspaceState.NOT_PROVISIONED,
    val profileRole: ProfileRole = ProfileRole.PERSONAL_CONTROLLER,
    val packageStatuses: Map<String, PackageStatusInfo> = emptyMap(),
    val statusMessage: String? = null
)

class FolderHomeViewModel : ViewModel() {

    private val appRepository = SecureFolderApp.instance.appRepository
    private val provisioningManager = SecureFolderApp.instance.provisioningManager
    private val crossProfileBridge = SecureFolderApp.instance.crossProfileBridge
    private val profileManager = SecureFolderApp.instance.profileManager

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        checkProfileStatus()
        observeApps()
        viewModelScope.launch {
            appRepository.refreshHostApps()
        }
    }

    fun checkProfileStatus() {
        val state = provisioningManager.getWorkspaceState()
        val role = provisioningManager.getRole()

        _uiState.value = _uiState.value.copy(
            workspaceState = state,
            profileRole = role
        )

        appRepository.loadClonedApps()
    }

    private fun observeApps() {
        viewModelScope.launch {
            appRepository.clonedApps.collect { apps ->
                _uiState.value = _uiState.value.copy(clonedApps = apps)
            }
        }
        viewModelScope.launch {
            appRepository.availableHostApps.collect { apps ->
                _uiState.value = _uiState.value.copy(availableHostApps = apps)
            }
        }
    }

    fun openAddAppSheet() {
        _uiState.value = _uiState.value.copy(isAddAppSheetOpen = true, searchQuery = "")
        viewModelScope.launch {
            appRepository.refreshHostApps()
        }
    }

    fun closeAddAppSheet() {
        _uiState.value = _uiState.value.copy(isAddAppSheetOpen = false, searchQuery = "")
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    /**
     * Initiates clone operation through the Managed Executor.
     * Prevents duplicate clicks, validates workspace state, updates per-package status,
     * and keeps the bottom sheet open until outcome is determined.
     */
    fun requestClone(app: ClonedApp, onDispatchCommand: (Intent) -> Unit) {
        val currentStatus = _uiState.value.packageStatuses[app.packageName]?.state
        if (currentStatus == PackageOpState.CLONING || currentStatus == PackageOpState.REMOVING) {
            return
        }

        // Validate workspace state
        when (_uiState.value.workspaceState) {
            WorkspaceState.NOT_PROVISIONED -> {
                setPackageStatus(app.packageName, PackageOpState.FAILED, "Workspace chưa được thiết lập")
                return
            }
            WorkspaceState.PAUSED -> {
                setPackageStatus(app.packageName, PackageOpState.FAILED, "Workspace đang tạm dừng (Quiet mode)")
                return
            }
            WorkspaceState.EXECUTOR_UNREACHABLE, WorkspaceState.ERROR -> {
                setPackageStatus(app.packageName, PackageOpState.FAILED, "Không thể kết nối đến Workspace")
                return
            }
            WorkspaceState.READY -> {
                // Proceed
            }
            WorkspaceState.PROVISIONING -> {
                setPackageStatus(app.packageName, PackageOpState.FAILED, "Workspace đang được khởi tạo...")
                return
            }
        }

        setPackageStatus(app.packageName, PackageOpState.CLONING, "Đang chuẩn bị APK an toàn…")

        viewModelScope.launch {
            try {
                val apkUris = appRepository.stageClonePackage(app.packageName)
                check(apkUris.isNotEmpty()) { "Không tìm thấy APK của ứng dụng" }

                setPackageStatus(
                    app.packageName,
                    PackageOpState.CLONING,
                    "Đang thêm vào Private Workspace…"
                )
                onDispatchCommand(
                    crossProfileBridge.createCloneIntent(app.packageName, apkUris)
                )
            } catch (e: Exception) {
                appRepository.cleanupStagedClone(app.packageName)
                setPackageStatus(
                    app.packageName,
                    PackageOpState.FAILED,
                    "Không thể chuẩn bị/cài ứng dụng: ${e.message}"
                )
            }
        }
    }

    /**
     * Handles the outcome returned by ManagedExecutorActivity or direct invocation.
     */
    fun onCloneCommandResult(packageName: String, isSuccess: Boolean, errorMessage: String?) {
        viewModelScope.launch {
            try {
                if (isSuccess) {
                    val verified = appRepository.verifyAndApplyClone(packageName)
                    if (verified) {
                        setPackageStatus(packageName, PackageOpState.CLONED, "Đã thêm")
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Đã nhân bản ứng dụng thành công"
                        )
                    } else {
                        setPackageStatus(
                            packageName,
                            PackageOpState.FAILED,
                            "Cài đặt thành công nhưng chưa xác thực được"
                        )
                    }
                } else {
                    setPackageStatus(
                        packageName,
                        PackageOpState.FAILED,
                        errorMessage ?: "Không thể nhân bản vào Private Workspace"
                    )
                }
            } finally {
                appRepository.cleanupStagedClone(packageName)
                restoreWorkspaceGateForActiveSession()
            }
        }
    }

    /**
     * Initiates removal through Managed Executor.
     */
    fun requestRemove(app: ClonedApp, onDispatchCommand: (Intent) -> Unit) {
        val currentStatus = _uiState.value.packageStatuses[app.packageName]?.state
        if (currentStatus == PackageOpState.REMOVING) {
            return
        }

        setPackageStatus(app.packageName, PackageOpState.REMOVING, "Đang gỡ bỏ…")

        val intent = crossProfileBridge.createRemoveIntent(app.packageName)
        try {
            onDispatchCommand(intent)
        } catch (e: Exception) {
            setPackageStatus(app.packageName, PackageOpState.FAILED, "Không thể chuyển lệnh gỡ: ${e.message}")
        }
    }

    fun onRemoveCommandResult(packageName: String, isSuccess: Boolean, errorMessage: String?) {
        viewModelScope.launch {
            try {
                if (isSuccess) {
                    val verified = appRepository.verifyAndApplyRemoval(packageName)
                    if (verified) {
                        setPackageStatus(packageName, PackageOpState.AVAILABLE, null)
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Đã gỡ ứng dụng khỏi Private Workspace"
                        )
                    } else {
                        setPackageStatus(packageName, PackageOpState.FAILED, "Ứng dụng vẫn còn tồn tại trong workspace")
                    }
                } else {
                    setPackageStatus(packageName, PackageOpState.FAILED, errorMessage ?: "Gỡ ứng dụng thất bại")
                }
            } finally {
                restoreWorkspaceGateForActiveSession()
            }
        }
    }

    private suspend fun restoreWorkspaceGateForActiveSession() {
        val sessionManager = SecureFolderApp.instance.sessionManager
        when {
            sessionManager.isPrimarySession() -> profileManager.setProfileLocked(false)
            sessionManager.isDecoySession() -> profileManager.setProfileLocked(true)
        }
        appRepository.loadClonedApps()
    }

    fun dispatchCrossProfileCommand(
        activity: Activity,
        intent: Intent
    ): CrossProfileDispatchResult {
        val result = crossProfileBridge.dispatchToManagedProfile(activity, intent)
        if (result.state == CrossProfileDispatchState.DISPATCHED) {
            monitorDispatchedCommand(intent)
        }
        return result
    }

    fun onCrossProfileDispatchFailure(intent: Intent, errorMessage: String?) {
        val packageName = intent.getStringExtra(
            com.example.core.profile.CrossProfileBridge.EXTRA_TARGET_PACKAGE
        ) ?: return

        when (intent.getStringExtra(com.example.core.profile.CrossProfileBridge.EXTRA_COMMAND)) {
            com.example.core.profile.CrossProfileBridge.COMMAND_CLONE ->
                onCloneCommandResult(packageName, false, errorMessage)
            com.example.core.profile.CrossProfileBridge.COMMAND_REMOVE ->
                onRemoveCommandResult(packageName, false, errorMessage)
        }
    }

    private fun monitorDispatchedCommand(intent: Intent) {
        val packageName = intent.getStringExtra(
            com.example.core.profile.CrossProfileBridge.EXTRA_TARGET_PACKAGE
        ) ?: return
        val command = intent.getStringExtra(
            com.example.core.profile.CrossProfileBridge.EXTRA_COMMAND
        ) ?: return

        viewModelScope.launch {
            // System installer/uninstaller UI can outlive the executor Activity. Only OS package
            // state is authoritative, so monitor that state instead of trusting local callbacks.
            repeat(90) {
                delay(1_000)
                val exists = withContext(Dispatchers.IO) {
                    appRepository.isPackageInWorkProfile(packageName)
                }

                when (command) {
                    com.example.core.profile.CrossProfileBridge.COMMAND_CLONE -> {
                        if (exists) {
                            onCloneCommandResult(packageName, true, null)
                            return@launch
                        }
                    }
                    com.example.core.profile.CrossProfileBridge.COMMAND_REMOVE -> {
                        if (!exists) {
                            onRemoveCommandResult(packageName, true, null)
                            return@launch
                        }
                    }
                }
            }

            when (command) {
                com.example.core.profile.CrossProfileBridge.COMMAND_CLONE ->
                    onCloneCommandResult(
                        packageName,
                        false,
                        "Cài đặt đã bị hủy, thất bại hoặc hết thời gian chờ"
                    )
                com.example.core.profile.CrossProfileBridge.COMMAND_REMOVE ->
                    onRemoveCommandResult(
                        packageName,
                        false,
                        "Gỡ cài đặt đã bị hủy, thất bại hoặc hết thời gian chờ"
                    )
            }
        }
    }

    private fun setPackageStatus(packageName: String, state: PackageOpState, message: String?) {
        val updated = _uiState.value.packageStatuses.toMutableMap()
        updated[packageName] = PackageStatusInfo(state, message)
        _uiState.value = _uiState.value.copy(packageStatuses = updated)
    }

    fun launchApp(app: ClonedApp) {
        // Deliberate workspace launch belongs to the current authenticated session. Keep a bounded
        // lease so MainActivity's foreground-leave hook does not immediately suspend the app.
        com.example.ExternalSystemFlowLease.acquireLease(120_000L)
        val launched = appRepository.launchClonedApp(app)
        if (!launched) {
            com.example.ExternalSystemFlowLease.releaseLease()
            _uiState.value = _uiState.value.copy(
                statusMessage = "Không thể mở ứng dụng trong Private Workspace. Kiểm tra trạng thái hồ sơ."
            )
        }
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    fun getProvisioningIntent(): Intent {
        return provisioningManager.getProvisioningIntent()
    }
}
