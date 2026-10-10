package com.example.ui.home

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.profile.CrossProfileBridge
import com.example.core.profile.PackageOpState
import com.example.core.profile.WorkspaceState
import com.example.data.model.ClonedApp
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
import com.example.viewmodel.FolderHomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderHomeScreen(
    viewModel: FolderHomeViewModel,
    onOpenVault: (filterType: String?) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onLockFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    var selectedAppForMenu by remember { mutableStateOf<ClonedApp?>(null) }

    // Launcher for Managed Profile Provisioning
    val provisioningLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.checkProfileStatus()
    }

    // Launcher for Cross-Profile Command Execution (CLONE)
    val cloneCommandLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val targetPkg = data?.getStringExtra(CrossProfileBridge.EXTRA_TARGET_PACKAGE)
        val success = result.resultCode == Activity.RESULT_OK &&
                data?.getBooleanExtra(CrossProfileBridge.EXTRA_RESULT_SUCCESS, false) == true
        val errorMsg = data?.getStringExtra(CrossProfileBridge.EXTRA_ERROR_MESSAGE)

        if (!targetPkg.isNullOrBlank()) {
            viewModel.onCloneCommandResult(targetPkg, success, errorMsg)
        }
    }

    // Launcher for Cross-Profile Command Execution (REMOVE)
    val removeCommandLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val targetPkg = data?.getStringExtra(CrossProfileBridge.EXTRA_TARGET_PACKAGE)
        val success = result.resultCode == Activity.RESULT_OK &&
                data?.getBooleanExtra(CrossProfileBridge.EXTRA_RESULT_SUCCESS, false) == true
        val errorMsg = data?.getStringExtra(CrossProfileBridge.EXTRA_ERROR_MESSAGE)

        if (!targetPkg.isNullOrBlank()) {
            viewModel.onRemoveCommandResult(targetPkg, success, errorMsg)
        }
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = OneUiDarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(OneUiTealPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = "Thư mục Bảo mật",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = OneUiDarkTextPrimary,
                                fontSize = 20.sp
                            )
                        )
                    }
                },
                actions = {
                    // Lock Folder Button
                    IconButton(
                        onClick = onLockFolder,
                        modifier = Modifier.testTag("lock_folder_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Khóa Thư mục Bảo mật",
                            tint = OneUiTealBright
                        )
                    }

                    // Add Apps Button
                    IconButton(
                        onClick = { viewModel.openAddAppSheet() },
                        modifier = Modifier.testTag("add_app_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Thêm ứng dụng",
                            tint = OneUiDarkTextPrimary
                        )
                    }

                    // Overflow Menu
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Tùy chọn",
                            tint = OneUiDarkTextPrimary
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(OneUiDarkCard)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Cài đặt Thư mục Bảo mật", color = OneUiDarkTextPrimary) },
                            leadingIcon = { Icon(Icons.Default.Settings, null, tint = OneUiTealBright) },
                            onClick = {
                                showMenu = false
                                onOpenSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Bộ sưu tập mã hóa", color = OneUiDarkTextPrimary) },
                            leadingIcon = { Icon(Icons.Default.PhotoLibrary, null, tint = OneUiFolderBlue) },
                            onClick = {
                                showMenu = false
                                onOpenVault(null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Máy ảnh an toàn", color = OneUiDarkTextPrimary) },
                            leadingIcon = { Icon(Icons.Default.CameraAlt, null, tint = OneUiTealPrimary) },
                            onClick = {
                                showMenu = false
                                onOpenCamera()
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OneUiDarkBackground
                )
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Enterprise Work Profile Status Banner
            item(span = { GridItemSpan(4) }) {
                WorkProfileStatusCard(
                    state = state,
                    onSetupWorkProfile = {
                        try {
                            provisioningLauncher.launch(viewModel.getProvisioningIntent())
                        } catch (e: Exception) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Thiết bị chưa hỗ trợ kích hoạt hồ sơ tự động.")
                            }
                        }
                    },
                    onRefreshProfile = {
                        viewModel.checkProfileStatus()
                    }
                )
            }

            // Built-in Vault Apps Header
            item(span = { GridItemSpan(4) }) {
                Text(
                    text = "ỨNG DỤNG BẢO MẬT GỐC",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = OneUiDarkTextSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Built-in Vault Apps: Gallery, Camera, Notes, Docs
            item {
                BuiltInAppItem(
                    icon = Icons.Default.PhotoLibrary,
                    title = "Bộ sưu tập",
                    accentColor = OneUiFolderBlue,
                    onClick = { onOpenVault("IMAGE") }
                )
            }
            item {
                BuiltInAppItem(
                    icon = Icons.Default.CameraAlt,
                    title = "Máy ảnh",
                    accentColor = OneUiTealBright,
                    onClick = onOpenCamera
                )
            }
            item {
                BuiltInAppItem(
                    icon = Icons.Default.NoteAlt,
                    title = "Ghi chú",
                    accentColor = OneUiShieldGold,
                    onClick = { onOpenVault("NOTE") }
                )
            }
            item {
                BuiltInAppItem(
                    icon = Icons.Default.Description,
                    title = "Tệp tin",
                    accentColor = Color(0xFF9B51E0),
                    onClick = { onOpenVault(null) }
                )
            }

            // Cloned Apps Header
            item(span = { GridItemSpan(4) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ỨNG DỤNG NHÂN BẢN (${state.clonedApps.size})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = OneUiDarkTextSecondary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    TextButton(onClick = { viewModel.openAddAppSheet() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = OneUiTealBright,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Thêm ứng dụng",
                            color = OneUiTealBright,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // Cloned Apps List
            if (state.clonedApps.isEmpty()) {
                item(span = { GridItemSpan(4) }) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = OneUiDarkCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OneUiDarkBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(OneUiTealPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = OneUiTealBright,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Chưa có ứng dụng nào được nhân bản",
                                color = OneUiDarkTextPrimary,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Chọn bất kỳ ứng dụng nào trên máy (Zalo, Facebook, Chrome, Telegram...) để nhân bản vào không gian cô lập.",
                                color = OneUiDarkTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.openAddAppSheet() },
                                colors = ButtonDefaults.buttonColors(containerColor = OneUiTealPrimary),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .testTag("empty_state_add_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Nhân bản ứng dụng ngay (+)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Quick suggestions if available
                            if (state.availableHostApps.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Gợi ý ứng dụng có sẵn trên máy:",
                                    color = OneUiDarkTextSecondary,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val quickList = state.availableHostApps.take(3)
                                    for (app in quickList) {
                                        val opStatus = state.packageStatuses[app.packageName]?.state
                                        val isOperating = opStatus == PackageOpState.CLONING

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(OneUiDarkSurface)
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                val bitmap = remember(app.icon) {
                                                    app.icon?.let { drawableToBitmap(it) }
                                                }
                                                if (bitmap != null) {
                                                    Image(
                                                        bitmap = bitmap.asImageBitmap(),
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                    )
                                                } else {
                                                    Icon(
                                                        Icons.Default.Folder,
                                                        contentDescription = null,
                                                        tint = OneUiTealBright,
                                                        modifier = Modifier.size(32.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = app.appLabel,
                                                    color = OneUiDarkTextPrimary,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    viewModel.requestClone(app) { intent ->
                                                        cloneCommandLauncher.launch(intent)
                                                    }
                                                },
                                                enabled = !isOperating,
                                                colors = ButtonDefaults.buttonColors(containerColor = OneUiTealPrimary.copy(alpha = 0.2f)),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                if (isOperating) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        strokeWidth = 2.dp,
                                                        color = OneUiTealBright
                                                    )
                                                } else {
                                                    Text("Nhân bản", color = OneUiTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                items(state.clonedApps, key = { it.packageName }) { app ->
                    ClonedAppItem(
                        app = app,
                        onClick = { viewModel.launchApp(app) },
                        onLongClick = { selectedAppForMenu = app }
                    )
                }
            }
        }
    }

    // App Options Dialog (when long-pressing a cloned app)
    if (selectedAppForMenu != null) {
        val app = selectedAppForMenu!!
        AppOptionsBottomSheet(
            app = app,
            onDismiss = { selectedAppForMenu = null },
            onLaunch = {
                selectedAppForMenu = null
                viewModel.launchApp(app)
            },
            onRemove = {
                selectedAppForMenu = null
                viewModel.requestRemove(app) { intent ->
                    removeCommandLauncher.launch(intent)
                }
            }
        )
    }

    // Add Apps Modal Bottom Sheet
    if (state.isAddAppSheetOpen) {
        AddAppBottomSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.closeAddAppSheet() },
            onLaunchCloneIntent = { intent ->
                cloneCommandLauncher.launch(intent)
            }
        )
    }
}

/**
 * P1 UX: Clean, semantic Work Profile status component.
 * Clear states: NOT_PROVISIONED, READY, PAUSED, ERROR.
 */
@Composable
private fun WorkProfileStatusCard(
    state: com.example.viewmodel.HomeUiState,
    onSetupWorkProfile: () -> Unit,
    onRefreshProfile: () -> Unit
) {
    val workspaceState = state.workspaceState
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = OneUiDarkCard
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, OneUiDarkBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (workspaceState) {
                WorkspaceState.READY -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = OneUiTealBright,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Private Workspace",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = OneUiDarkTextPrimary,
                                    fontSize = 15.sp
                                )
                            )
                            Text(
                                text = "Ready · Apps isolated in Work Profile",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = OneUiTealBright,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Không gian bảo mật đang hoạt động. Ứng dụng nhân bản được cô lập hoàn toàn dưới Android Enterprise và tự động đóng băng khi khóa Thư mục.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = OneUiDarkTextSecondary,
                            lineHeight = 18.sp
                        )
                    )
                }

                WorkspaceState.NOT_PROVISIONED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = OneUiShieldGold,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Private Workspace",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = OneUiDarkTextPrimary,
                                    fontSize = 15.sp
                                )
                            )
                            Text(
                                text = "Not configured",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = OneUiShieldGold,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Vault mã hóa dữ liệu cục bộ hoạt động bình thường. Để nhân bản và cách ly các ứng dụng khác, cần thiết lập Private Workspace.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = OneUiDarkTextSecondary,
                            lineHeight = 18.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onSetupWorkProfile,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OneUiTealPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_workspace_button")
                    ) {
                        Text("Set up Private Workspace", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                WorkspaceState.PAUSED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PauseCircle,
                            contentDescription = null,
                            tint = OneUiShieldGold,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Private Workspace",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = OneUiDarkTextPrimary,
                                    fontSize = 15.sp
                                )
                            )
                            Text(
                                text = "Paused",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = OneUiShieldGold,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Không gian làm việc đang trong chế độ tạm dừng. Các ứng dụng nhân bản đã được đóng băng tiến trình nền.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = OneUiDarkTextSecondary,
                            lineHeight = 18.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onRefreshProfile,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OneUiShieldGold.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Resume Workspace", color = OneUiShieldGold, fontWeight = FontWeight.Bold)
                    }
                }

                WorkspaceState.EXECUTOR_UNREACHABLE, WorkspaceState.ERROR -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = OneUiLockRed,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Private Workspace",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = OneUiDarkTextPrimary,
                                    fontSize = 15.sp
                                )
                            )
                            Text(
                                text = "Connection problem",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = OneUiLockRed,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Không thể kết nối với executor trong Work Profile.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = OneUiDarkTextSecondary,
                            lineHeight = 18.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onRefreshProfile,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OneUiLockRed.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Retry", color = OneUiLockRed, fontWeight = FontWeight.Bold)
                    }
                }

                WorkspaceState.PROVISIONING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = OneUiTealBright
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Đang thiết lập Private Workspace...",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = OneUiDarkTextPrimary,
                                fontSize = 15.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltInAppItem(
    icon: ImageVector,
    title: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(accentColor.copy(alpha = 0.15f))
                .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(
                color = OneUiDarkTextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ClonedAppItem(
    app: ClonedApp,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bitmap = remember(app.icon) {
        app.icon?.let { drawableToBitmap(it) }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
            .testTag("cloned_app_${app.packageName}")
    ) {
        Box(
            modifier = Modifier.size(60.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = app.appLabel,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(OneUiDarkCard),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = OneUiTealBright,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Private Workspace Badge on bottom right of the icon
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(OneUiTealPrimary)
                    .border(1.5.dp, OneUiDarkBackground, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Workspace Protected",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = app.appLabel,
            style = MaterialTheme.typography.bodySmall.copy(
                color = OneUiDarkTextPrimary,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppOptionsBottomSheet(
    app: ClonedApp,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onRemove: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OneUiDarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = app.appLabel,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = OneUiDarkTextPrimary
                )
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = OneUiDarkTextSecondary
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onLaunch,
                colors = ButtonDefaults.buttonColors(containerColor = OneUiTealPrimary),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Mở ứng dụng trong Private Workspace", color = Color.White)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onRemove,
                colors = ButtonDefaults.buttonColors(containerColor = OneUiLockRed.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Gỡ khỏi Private Workspace", color = OneUiLockRed)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * P1 UX: Enhanced Add App Modal Bottom Sheet.
 * - Single interaction target per row (only button triggers action).
 * - Per-package state: AVAILABLE, CLONING (spinner), CLONED (checkmark), FAILED (error message & retry).
 * - Does not close bottom sheet until outcome is determined.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppBottomSheet(
    viewModel: FolderHomeViewModel,
    onDismiss: () -> Unit,
    onLaunchCloneIntent: (android.content.Intent) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filteredApps = remember(state.availableHostApps, state.searchQuery) {
        if (state.searchQuery.isBlank()) {
            state.availableHostApps
        } else {
            state.availableHostApps.filter {
                it.appLabel.contains(state.searchQuery, ignoreCase = true) ||
                        it.packageName.contains(state.searchQuery, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OneUiDarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Thêm ứng dụng vào Private Workspace",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = OneUiDarkTextPrimary
                )
            )
            Text(
                text = "Ứng dụng sẽ được nhân bản và chạy trong không gian Work Profile độc lập.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = OneUiDarkTextSecondary
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                placeholder = { Text("Tìm kiếm ứng dụng...", color = OneUiDarkTextSecondary) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_app_input"),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OneUiTealPrimary,
                    unfocusedBorderColor = OneUiDarkBorder,
                    focusedTextColor = OneUiDarkTextPrimary,
                    unfocusedTextColor = OneUiDarkTextPrimary,
                    focusedContainerColor = OneUiDarkCard,
                    unfocusedContainerColor = OneUiDarkCard
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Không tìm thấy ứng dụng phù hợp",
                        color = OneUiDarkTextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val packageStatus = state.packageStatuses[app.packageName]
                        val opState = packageStatus?.state ?: PackageOpState.AVAILABLE
                        val isOperating = opState == PackageOpState.CLONING

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(OneUiDarkCard)
                                .padding(12.dp)
                                .testTag("add_item_${app.packageName}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val bitmap = remember(app.icon) {
                                app.icon?.let { drawableToBitmap(it) }
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(OneUiTealPrimary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = OneUiTealBright
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appLabel,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = OneUiDarkTextPrimary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = OneUiDarkTextSecondary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (packageStatus?.message != null) {
                                    val msgColor = if (opState == PackageOpState.FAILED) OneUiLockRed else OneUiTealBright
                                    Text(
                                        text = packageStatus.message,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = msgColor
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // One single interactive target per row
                            when (opState) {
                                PackageOpState.CLONING -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = OneUiTealBright
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Adding...",
                                            color = OneUiDarkTextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                PackageOpState.CLONED -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = OneUiTealBright,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Added",
                                            color = OneUiTealBright,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                PackageOpState.FAILED -> {
                                    Button(
                                        onClick = {
                                            viewModel.requestClone(app, onLaunchCloneIntent)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = OneUiLockRed.copy(alpha = 0.2f))
                                    ) {
                                        Text("Retry", color = OneUiLockRed, fontWeight = FontWeight.Bold)
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = {
                                            viewModel.requestClone(app, onLaunchCloneIntent)
                                        },
                                        enabled = !isOperating,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = OneUiTealPrimary)
                                    ) {
                                        Text("Thêm", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
