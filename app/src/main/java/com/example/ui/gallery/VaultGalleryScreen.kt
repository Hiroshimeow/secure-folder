package com.example.ui.gallery

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.VaultItemType
import com.example.data.model.VaultMediaItem
import com.example.data.repository.ImportMode
import com.example.ui.theme.OneUiDarkBackground
import com.example.ui.theme.OneUiDarkBorder
import com.example.ui.theme.OneUiDarkCard
import com.example.ui.theme.OneUiDarkSurface
import com.example.ui.theme.OneUiDarkTextPrimary
import com.example.ui.theme.OneUiDarkTextSecondary
import com.example.ui.theme.OneUiFolderBlue
import com.example.ui.theme.OneUiLockRed
import com.example.ui.theme.OneUiShieldGold
import com.example.ui.theme.OneUiTealBright
import com.example.ui.theme.OneUiTealPrimary
import com.example.viewmodel.VaultViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultGalleryScreen(
    viewModel: VaultViewModel,
    initialFilter: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showImportModeDialog by remember { mutableStateOf(false) }
    var selectedImportMode by remember { mutableStateOf(ImportMode.COPY) }

    BackHandler { onBack() }

    LaunchedEffect(initialFilter) {
        if (initialFilter != null) {
            try {
                viewModel.setFilter(VaultItemType.valueOf(initialFilter))
            } catch (e: Exception) {
                viewModel.setFilter(null)
            }
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // OS Delete Consent Request Launcher (Android 11+ MediaStore.createDeleteRequest)
    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        viewModel.onOsDeleteConsentResult(granted)
    }

    LaunchedEffect(state.pendingDeleteIntentSender) {
        state.pendingDeleteIntentSender?.let { sender ->
            try {
                com.example.MainActivity.isLaunchingSystemIntent = true
                intentSenderLauncher.launch(
                    IntentSenderRequest.Builder(sender).build()
                )
            } catch (e: Exception) {
                com.example.MainActivity.isLaunchingSystemIntent = false
                viewModel.onOsDeleteConsentResult(false)
            }
        }
    }

    // System File / Document Picker for Safe Ingestion
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val mime = context.contentResolver.getType(it) ?: "application/octet-stream"
            val name = "Tệp_${System.currentTimeMillis() % 10000}"
            viewModel.importFile(it, name, mime, selectedImportMode)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = OneUiDarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Bộ sưu tập Mã hóa",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = OneUiDarkTextPrimary
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                            tint = OneUiDarkTextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showImportModeDialog = true },
                        modifier = Modifier.testTag("import_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "Nhập tệp từ thiết bị",
                            tint = OneUiTealBright
                        )
                    }
                    IconButton(
                        onClick = { viewModel.openNewNoteEditor() },
                        modifier = Modifier.testTag("add_note_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.NoteAdd,
                            contentDescription = "Tạo ghi chú bảo mật",
                            tint = OneUiShieldGold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OneUiDarkBackground)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.selectedFilter == null,
                    onClick = { viewModel.setFilter(null) },
                    label = { Text("Tất cả (${state.items.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OneUiTealPrimary,
                        selectedLabelColor = Color.White,
                        containerColor = OneUiDarkCard,
                        labelColor = OneUiDarkTextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = state.selectedFilter == null,
                        borderColor = OneUiDarkBorder,
                        selectedBorderColor = OneUiTealPrimary
                    )
                )
                FilterChip(
                    selected = state.selectedFilter == VaultItemType.IMAGE,
                    onClick = { viewModel.setFilter(VaultItemType.IMAGE) },
                    label = { Text("Ảnh & Video") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OneUiFolderBlue,
                        selectedLabelColor = Color.White,
                        containerColor = OneUiDarkCard,
                        labelColor = OneUiDarkTextSecondary
                    )
                )
                FilterChip(
                    selected = state.selectedFilter == VaultItemType.NOTE,
                    onClick = { viewModel.setFilter(VaultItemType.NOTE) },
                    label = { Text("Ghi chú") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OneUiShieldGold,
                        selectedLabelColor = Color.Black,
                        containerColor = OneUiDarkCard,
                        labelColor = OneUiDarkTextSecondary
                    )
                )
            }

            val filteredList = remember(state.items, state.selectedFilter) {
                if (state.selectedFilter == null) state.items
                else state.items.filter { it.itemType == state.selectedFilter }
            }

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = OneUiDarkTextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Chưa có tệp tin nào trong Vault",
                            color = OneUiDarkTextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Nhấn biểu tượng tải lên hoặc chụp ảnh để lưu an toàn",
                            color = OneUiDarkTextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredList, key = { it.id }) { item ->
                        VaultItemThumbnail(
                            item = item,
                            onClick = { viewModel.openItem(item) }
                        )
                    }
                }
            }
        }
    }

    // Safe Import Choice Dialog: Copy vs Move
    if (showImportModeDialog) {
        AlertDialog(
            onDismissRequest = { showImportModeDialog = false },
            containerColor = OneUiDarkSurface,
            title = {
                Text(
                    text = "Phương thức nhập tệp",
                    color = OneUiDarkTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Chọn cách bạn muốn đưa tệp vào Thư mục Bảo mật:",
                        color = OneUiDarkTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Option 1: Copy
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedImportMode = ImportMode.COPY
                                showImportModeDialog = false
                                com.example.MainActivity.isLaunchingSystemIntent = true
                                filePicker.launch(arrayOf("*/*"))
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = OneUiDarkCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OneUiTealPrimary.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, null, tint = OneUiTealBright)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Sao chép an toàn (Copy)", color = OneUiDarkTextPrimary, fontWeight = FontWeight.Bold)
                                Text("Mã hóa bản sao vào Vault, giữ nguyên file gốc trên máy", color = OneUiDarkTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }

                    // Option 2: Move
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedImportMode = ImportMode.MOVE
                                showImportModeDialog = false
                                com.example.MainActivity.isLaunchingSystemIntent = true
                                filePicker.launch(arrayOf("*/*"))
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = OneUiDarkCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OneUiLockRed.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DriveFileMove, null, tint = OneUiLockRed)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Di chuyển & Xóa gốc (Move)", color = OneUiDarkTextPrimary, fontWeight = FontWeight.Bold)
                                Text("Mã hóa vào Vault, sau đó xin phép OS xóa file gốc", color = OneUiDarkTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportModeDialog = false }) {
                    Text("Đóng", color = OneUiDarkTextSecondary)
                }
            }
        )
    }

    // Fullscreen Decrypted Viewer Dialog
    if (state.isViewerOpen && state.selectedItem != null) {
        val item = state.selectedItem!!
        Dialog(onDismissRequest = { viewModel.closeViewer() }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.deleteItem(item) }) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = "Xóa an toàn",
                                tint = OneUiLockRed
                            )
                        }
                    }

                    // Content Viewer
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.decryptedBytes != null) {
                            val bitmap = remember(state.decryptedBytes) {
                                BitmapFactory.decodeByteArray(
                                    state.decryptedBytes,
                                    0,
                                    state.decryptedBytes!!.size
                                )
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = item.title,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (item.itemType == VaultItemType.VIDEO) {
                                // Full in-app Video Player with temporary isolated playback stream
                                val videoFile = remember(item.id, state.decryptedBytes) {
                                    val tempVideo = java.io.File(context.cacheDir, "sec_preview_${item.id}.mp4")
                                    state.decryptedBytes?.let { tempVideo.writeBytes(it) }
                                    tempVideo
                                }
                                DisposableEffect(videoFile) {
                                    onDispose {
                                        if (videoFile.exists()) {
                                            videoFile.delete()
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AndroidView(
                                        factory = { ctx ->
                                            VideoView(ctx).apply {
                                                setVideoURI(Uri.fromFile(videoFile))
                                                val mediaController = MediaController(ctx)
                                                mediaController.setAnchorView(this)
                                                setMediaController(mediaController)
                                                setOnPreparedListener { mp ->
                                                    mp.isLooping = true
                                                    start()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            } else {
                                // Document / Text / PDF preview
                                val textPreview = remember(state.decryptedBytes) {
                                    state.decryptedBytes?.let {
                                        try {
                                            String(it, Charsets.UTF_8).take(8000)
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .padding(20.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(OneUiDarkCard)
                                        .padding(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = null,
                                        tint = Color(0xFF9B51E0),
                                        modifier = Modifier.size(52.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = item.title,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Loại MIME: ${item.mimeType} • ${item.fileSize / 1024} KB",
                                        color = OneUiDarkTextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    if (!textPreview.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(200.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(OneUiDarkSurface)
                                                .padding(12.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            Text(
                                                text = textPreview,
                                                color = OneUiDarkTextPrimary,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "✓ Đã xác minh giải mã an toàn AES-256-GCM",
                                        color = OneUiTealBright,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        } else {
                            Text("Đang giải mã bằng KeyStore...", color = OneUiTealBright)
                        }
                    }

                    // Bottom Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = { viewModel.closeViewer() },
                            colors = ButtonDefaults.buttonColors(containerColor = OneUiDarkCard)
                        ) {
                            Text("Đóng", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Secure Note Atomic Editor / Viewer Dialog
    if (state.isNoteEditorOpen) {
        val selected = state.selectedItem
        var titleText by remember { mutableStateOf(selected?.title ?: "") }
        var contentText by remember { mutableStateOf(state.activeNoteContent ?: "") }

        AlertDialog(
            onDismissRequest = { viewModel.closeNoteEditor() },
            containerColor = OneUiDarkSurface,
            title = {
                Text(
                    text = if (selected == null) "Tạo Ghi chú Bảo mật" else "Ghi chú Bảo mật",
                    color = OneUiDarkTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = titleText,
                        onValueChange = { titleText = it },
                        label = { Text("Tiêu đề", color = OneUiDarkTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OneUiShieldGold,
                            unfocusedBorderColor = OneUiDarkBorder,
                            focusedTextColor = OneUiDarkTextPrimary,
                            unfocusedTextColor = OneUiDarkTextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = contentText,
                        onValueChange = { contentText = it },
                        label = { Text("Nội dung ghi chú", color = OneUiDarkTextSecondary) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OneUiShieldGold,
                            unfocusedBorderColor = OneUiDarkBorder,
                            focusedTextColor = OneUiDarkTextPrimary,
                            unfocusedTextColor = OneUiDarkTextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveNoteAtomic(titleText, contentText, selected?.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OneUiShieldGold)
                ) {
                    Text("Lưu mã hóa (Atomic)", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeNoteEditor() }) {
                    Text("Hủy", color = OneUiDarkTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun VaultItemThumbnail(
    item: VaultMediaItem,
    onClick: () -> Unit
) {
    val dateStr = remember(item.timestamp) {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(item.timestamp))
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(OneUiDarkCard)
            .border(1.dp, OneUiDarkBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (item.itemType) {
                VaultItemType.IMAGE -> {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = OneUiFolderBlue,
                        modifier = Modifier.size(36.dp)
                    )
                }
                VaultItemType.VIDEO -> {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = OneUiLockRed,
                        modifier = Modifier.size(36.dp)
                    )
                }
                VaultItemType.NOTE -> {
                    Icon(
                        imageVector = Icons.Default.NoteAlt,
                        contentDescription = null,
                        tint = OneUiShieldGold,
                        modifier = Modifier.size(36.dp)
                    )
                }
                VaultItemType.DOCUMENT -> {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = Color(0xFF9B51E0),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.title,
                color = OneUiDarkTextPrimary,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = dateStr,
                color = OneUiDarkTextSecondary,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
            )
        }
    }
}
