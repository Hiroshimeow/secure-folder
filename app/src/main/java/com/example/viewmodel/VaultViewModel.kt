package com.example.viewmodel

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.SecureFolderApp
import com.example.data.model.VaultItemType
import com.example.data.model.VaultMediaItem
import com.example.data.repository.ImportMode
import com.example.data.repository.SafeImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VaultUiState(
    val items: List<VaultMediaItem> = emptyList(),
    val selectedFilter: VaultItemType? = null,
    val selectedItem: VaultMediaItem? = null,
    val decryptedBytes: ByteArray? = null,
    val activeNoteContent: String? = null,
    val isNoteEditorOpen: Boolean = false,
    val isViewerOpen: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val pendingDeleteIntentSender: IntentSender? = null,
    val pendingMoveItemTitle: String? = null
)

class VaultViewModel : ViewModel() {

    private val vaultRepository = SecureFolderApp.instance.vaultRepository
    private val sessionManager = SecureFolderApp.instance.sessionManager

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            vaultRepository.vaultItems.collect { list ->
                _uiState.value = _uiState.value.copy(items = list)
            }
        }

        // Observe session authorization: when invalidated (locked), purge all plaintext in RAM
        viewModelScope.launch {
            sessionManager.isAuthenticated.collect { authed ->
                if (!authed) {
                    purgePlaintextInMemory()
                }
            }
        }
    }

    fun purgePlaintextInMemory() {
        _uiState.value = _uiState.value.copy(
            decryptedBytes = null,
            activeNoteContent = null,
            isViewerOpen = false,
            isNoteEditorOpen = false,
            selectedItem = null
        )
    }

    fun setFilter(filter: VaultItemType?) {
        _uiState.value = _uiState.value.copy(selectedFilter = filter)
    }

    /**
     * Executes the Safe Import pipeline (Copy or Move).
     */
    fun importFile(uri: Uri, name: String, mimeType: String, mode: ImportMode) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = vaultRepository.importFileSafely(uri, name, mimeType, mode)

            if (result.isSuccess) {
                val importRes = result.getOrThrow()
                if (mode == ImportMode.MOVE) {
                    if (importRes.pendingDeleteIntentSender != null) {
                        // OS consent needed to delete original
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            pendingDeleteIntentSender = importRes.pendingDeleteIntentSender,
                            pendingMoveItemTitle = importRes.item.title,
                            message = "Đã lưu vào Vault. Vui lòng cho phép xóa file gốc trên thiết bị."
                        )
                    } else if (importRes.sourceDeleted) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            message = "Đã di chuyển an toàn và xóa bản gốc khỏi thiết bị."
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            message = "Đã lưu vào Vault an toàn (File gốc được giữ nguyên: SOURCE_RETAINED)."
                        )
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "Đã sao chép và mã hóa vào Thư mục Bảo mật (Bản gốc còn nguyên)."
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Lỗi khi nhập tệp: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun onOsDeleteConsentResult(granted: Boolean) {
        val title = _uiState.value.pendingMoveItemTitle ?: "Tệp"
        _uiState.value = _uiState.value.copy(
            pendingDeleteIntentSender = null,
            pendingMoveItemTitle = null,
            message = if (granted) {
                "Đã xóa bản gốc của $title thành công."
            } else {
                "Đã lưu $title trong Vault (Bản gốc giữ nguyên do bạn từ chối quyền xóa)."
            }
        )
    }

    fun saveNoteAtomic(title: String, content: String, existingId: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = vaultRepository.saveSecureNoteAtomic(existingId, title, content)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isNoteEditorOpen = false,
                message = if (result.isSuccess) "Đã lưu ghi chú bảo mật (Đã xác minh mã hóa)" else "Lỗi khi lưu ghi chú"
            )
        }
    }

    fun openItem(item: VaultMediaItem) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, selectedItem = item)
            if (item.itemType == VaultItemType.NOTE) {
                val content = vaultRepository.readNoteContent(item)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    activeNoteContent = content,
                    isNoteEditorOpen = true
                )
            } else {
                val bytes = vaultRepository.decryptItemBytes(item)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    decryptedBytes = bytes,
                    isViewerOpen = true
                )
            }
        }
    }

    fun closeViewer() {
        _uiState.value = _uiState.value.copy(
            isViewerOpen = false,
            selectedItem = null,
            decryptedBytes = null
        )
    }

    fun openNewNoteEditor() {
        _uiState.value = _uiState.value.copy(
            isNoteEditorOpen = true,
            selectedItem = null,
            activeNoteContent = ""
        )
    }

    fun closeNoteEditor() {
        _uiState.value = _uiState.value.copy(
            isNoteEditorOpen = false,
            selectedItem = null,
            activeNoteContent = null
        )
    }

    fun deleteItem(item: VaultMediaItem) {
        viewModelScope.launch {
            vaultRepository.securelyDeleteItem(item)
            closeViewer()
            closeNoteEditor()
            _uiState.value = _uiState.value.copy(message = "Đã ghi đè và xóa an toàn khỏi bộ nhớ")
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
