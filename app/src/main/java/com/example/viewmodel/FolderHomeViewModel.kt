package com.example.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.SecureFolderApp
import com.example.core.profile.PackageOpState
import com.example.core.profile.PackageStatusInfo
import com.example.core.profile.ProfileRole
import com.example.core.profile.WorkspaceState
import com.example.data.model.ClonedApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val profileManager = SecureFolderApp.instance.profileManager
    private val crossProfileBridge = SecureFolderApp.instance.crossProfileBridge

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

        setPackageStatus(app.packageName, PackageOpState.CLONING, "Đang thêm vào Private Workspace…")

        // If running in managed profile with Profile Owner directly (e.g. testing)
        if (provisioningManager.isProfileOwner()) {
            viewModelScope.launch {
                val directSuccess = profileManager.cloneAppIntoSecureFolder(app.packageName)
                onCloneCommandResult(app.packageName, directSuccess, if (directSuccess) null else "Cài đặt thất bại")
            }
            return
        }

        // Cross-profile command dispatch to Managed Executor
        val intent = crossProfileBridge.createCloneIntent(app.packageName)
        try {
            onDispatchCommand(intent)
        } catch (e: Exception) {
            setPackageStatus(app.packageName, PackageOpState.FAILED, "Không thể chuyển lệnh: ${e.message}")
        }
    }

    /**
     * Handles the outcome returned by ManagedExecutorActivity or direct invocation.
     */
    fun onCloneCommandResult(packageName: String, isSuccess: Boolean, errorMessage: String?) {
        viewModelScope.launch {
            if (isSuccess) {
                val verified = appRepository.verifyAndApplyClone(packageName)
                if (verified) {
                    setPackageStatus(packageName, PackageOpState.CLONED, "Đã thêm")
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Đã nhân bản ứng dụng thành công"
                    )
                } else {
                    setPackageStatus(packageName, PackageOpState.FAILED, "Cài đặt thành công nhưng chưa xác thực được")
                }
            } else {
                setPackageStatus(
                    packageName,
                    PackageOpState.FAILED,
                    errorMessage ?: "Không thể nhân bản vào Private Workspace"
                )
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

        if (provisioningManager.isProfileOwner()) {
            viewModelScope.launch {
                val directSuccess = profileManager.uncloneApp(app.packageName)
                onRemoveCommandResult(app.packageName, directSuccess, if (directSuccess) null else "Gỡ ứng dụng thất bại")
            }
            return
        }

        val intent = crossProfileBridge.createRemoveIntent(app.packageName)
        try {
            onDispatchCommand(intent)
        } catch (e: Exception) {
            setPackageStatus(app.packageName, PackageOpState.FAILED, "Không thể chuyển lệnh gỡ: ${e.message}")
        }
    }

    fun onRemoveCommandResult(packageName: String, isSuccess: Boolean, errorMessage: String?) {
        viewModelScope.launch {
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
        }
    }

    private fun setPackageStatus(packageName: String, state: PackageOpState, message: String?) {
        val updated = _uiState.value.packageStatuses.toMutableMap()
        updated[packageName] = PackageStatusInfo(state, message)
        _uiState.value = _uiState.value.copy(packageStatuses = updated)
    }

    fun launchApp(app: ClonedApp) {
        val launched = appRepository.launchClonedApp(app)
        if (!launched) {
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
