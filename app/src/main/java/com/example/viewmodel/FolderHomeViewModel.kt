package com.example.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.SecureFolderApp
import com.example.admin.ProfileEnvironment
import com.example.admin.ProvisioningState
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
    val isLoading: Boolean = false,
    val provisioningState: ProvisioningState = ProvisioningState.NOT_PROVISIONED,
    val profileEnvironment: ProfileEnvironment = ProfileEnvironment.MAIN_PROFILE,
    val isProfileOwner: Boolean = false,
    val statusMessage: String? = null
)

class FolderHomeViewModel : ViewModel() {

    private val appRepository = SecureFolderApp.instance.appRepository
    private val provisioningManager = SecureFolderApp.instance.provisioningManager
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
        val env = provisioningManager.getEnvironment()
        val isOwner = provisioningManager.isProfileOwner()
        val provState = provisioningManager.checkProvisioningState()

        _uiState.value = _uiState.value.copy(
            profileEnvironment = env,
            isProfileOwner = isOwner,
            provisioningState = provState
        )
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

    fun cloneApp(app: ClonedApp) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val success = appRepository.cloneApp(app)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isAddAppSheetOpen = false,
                statusMessage = if (success) {
                    "Đã nhân bản ${app.appLabel} vào hồ sơ bảo mật thành công"
                } else {
                    "Không thể nhân bản: Chưa kích hoạt Profile Owner (Android Enterprise Work Profile)"
                }
            )
        }
    }

    fun removeClonedApp(packageName: String) {
        viewModelScope.launch {
            appRepository.removeClonedApp(packageName)
            _uiState.value = _uiState.value.copy(statusMessage = "Đã gỡ ứng dụng khỏi Thư mục Bảo mật")
        }
    }

    fun launchApp(app: ClonedApp) {
        appRepository.launchClonedApp(app)
    }

    fun getProvisioningIntent(): Intent {
        return provisioningManager.createProvisioningIntent()
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }
}
