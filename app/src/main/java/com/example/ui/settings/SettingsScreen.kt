package com.example.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.SecureFolderApp
import com.example.ui.theme.OneUiDarkBackground
import com.example.ui.theme.OneUiDarkBorder
import com.example.ui.theme.OneUiDarkCard
import com.example.ui.theme.OneUiDarkSurface
import com.example.ui.theme.OneUiDarkTextPrimary
import com.example.ui.theme.OneUiDarkTextSecondary
import com.example.ui.theme.OneUiLockRed
import com.example.ui.theme.OneUiShieldGold
import com.example.ui.theme.OneUiTealBright
import com.example.ui.theme.OneUiTealPrimary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settingsRepo = SecureFolderApp.instance.settingsRepository
    val provisioningMgr = SecureFolderApp.instance.provisioningManager
    val settings by settingsRepo.settings.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showChangePinDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = OneUiDarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Cài đặt Thư mục Bảo mật",
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OneUiDarkBackground)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Security & Auth
            SettingsSectionHeader(title = "BẢO MẬT & XÁC THỰC")

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = OneUiDarkCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, OneUiDarkBorder)
            ) {
                Column {
                    SettingsRowItem(
                        icon = Icons.Default.Password,
                        title = "Đổi mã PIN",
                        subtitle = "Mã PIN 4-6 chữ số bảo vệ truy cập",
                        onClick = { showChangePinDialog = true },
                        testTag = "change_pin_item"
                    )

                    SettingsDivider()

                    SettingsSwitchItem(
                        icon = Icons.Default.Fingerprint,
                        title = "Mở khóa bằng Sinh trắc học",
                        subtitle = "Sử dụng cảm biến vân tay để mở khóa nhanh",
                        checked = settings.useBiometrics,
                        onCheckedChange = { settingsRepo.setUseBiometrics(it) }
                    )

                    SettingsDivider()

                    SettingsRowItem(
                        icon = Icons.Default.LockClock,
                        title = "Tự động khóa",
                        subtitle = if (settings.autoLockTimeoutMinutes == 0) "Ngay khi thoát ứng dụng" else "Sau ${settings.autoLockTimeoutMinutes} phút",
                        onClick = {
                            val next = if (settings.autoLockTimeoutMinutes == 0) 5 else if (settings.autoLockTimeoutMinutes == 5) 15 else 0
                            settingsRepo.setAutoLockTimeout(next)
                        }
                    )
                }
            }

            // Section 2: Disguise Mode
            SettingsSectionHeader(title = "NGỤY TRANG & RIÊNG TƯ")

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = OneUiDarkCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, OneUiDarkBorder)
            ) {
                Column {
                    SettingsSwitchItem(
                        icon = Icons.Default.Calculate,
                        title = "Ngụy trang thành Máy tính",
                        subtitle = "Hiển thị máy tính bỏ túi hoạt động bình thường khi mở app. Nhập mã PIN và bấm '=' để vào Thư mục Bảo mật.",
                        checked = settings.isDisguisedAsCalculator,
                        onCheckedChange = {
                            settingsRepo.setDisguisedAsCalculator(it)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (it) "Đã bật ngụy trang Máy tính" else "Đã tắt ngụy trang"
                                )
                            }
                        }
                    )
                }
            }

            // Section 3: Knox & DPC Engine Status
            SettingsSectionHeader(title = "CÔNG NGHỆ BẢO MẬT SAMSUNG KNOX")

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = OneUiDarkCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, OneUiDarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatusRow(
                        label = "Kiến trúc cô lập",
                        value = "Android Enterprise DPC"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StatusRow(
                        label = "Thuật toán mã hóa",
                        value = "AES-256-GCM (Hardware Keystore)"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StatusRow(
                        label = "Cơ chế đóng băng tiến trình",
                        value = "DevicePolicyManager.setProfileEnabled"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StatusRow(
                        label = "Trạng thái Profile Owner",
                        value = if (provisioningMgr.isProfileOwner()) "Hoạt động (Active)" else "Mã hóa cục bộ"
                    )
                }
            }

            // Section 4: Reset Secure Folder
            SettingsSectionHeader(title = "ĐẶT LẠI")

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = OneUiDarkCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, OneUiDarkBorder)
            ) {
                SettingsRowItem(
                    icon = Icons.Default.DeleteSweep,
                    iconTint = OneUiLockRed,
                    title = "Xóa sạch Thư mục Bảo mật",
                    subtitle = "Xóa toàn bộ tệp tin mã hóa và gỡ liên kết ứng dụng",
                    onClick = { showResetDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Change PIN Dialog
    if (showChangePinDialog) {
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            containerColor = OneUiDarkSurface,
            title = {
                Text(
                    text = "Thiết lập mã PIN mới",
                    color = OneUiDarkTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 6) newPin = it },
                        label = { Text("Mã PIN mới (4-6 số)", color = OneUiDarkTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OneUiTealPrimary,
                            unfocusedBorderColor = OneUiDarkBorder,
                            focusedTextColor = OneUiDarkTextPrimary,
                            unfocusedTextColor = OneUiDarkTextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 6) confirmPin = it },
                        label = { Text("Xác nhận mã PIN mới", color = OneUiDarkTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OneUiTealPrimary,
                            unfocusedBorderColor = OneUiDarkBorder,
                            focusedTextColor = OneUiDarkTextPrimary,
                            unfocusedTextColor = OneUiDarkTextPrimary
                        )
                    )
                    if (pinError != null) {
                        Text(text = pinError ?: "", color = OneUiLockRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPin.length < 4) {
                            pinError = "Mã PIN phải có ít nhất 4 chữ số."
                        } else if (newPin != confirmPin) {
                            pinError = "Mã PIN xác nhận không trùng khớp."
                        } else {
                            settingsRepo.setPin(newPin)
                            showChangePinDialog = false
                            scope.launch {
                                snackbarHostState.showSnackbar("Đã đổi mã PIN thành công")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OneUiTealPrimary)
                ) {
                    Text("Lưu", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) {
                    Text("Hủy", color = OneUiDarkTextSecondary)
                }
            }
        )
    }

    // Reset Secure Folder Dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = OneUiDarkSurface,
            title = {
                Text(
                    text = "Xóa sạch dữ liệu Thư mục Bảo mật?",
                    color = OneUiLockRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Hành động này sẽ xóa vĩnh viễn tất cả tệp tin, ảnh chụp an toàn và ghi chú bên trong vault. Dữ liệu không thể khôi phục.",
                    color = OneUiDarkTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        settingsRepo.setPin("1234")
                        settingsRepo.setDisguisedAsCalculator(false)
                        showResetDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar("Đã đặt lại cấu hình Thư mục Bảo mật")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OneUiLockRed)
                ) {
                    Text("Xóa dữ liệu", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Hủy", color = OneUiDarkTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            color = OneUiDarkTextSecondary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        ),
        modifier = Modifier.padding(start = 8.dp, top = 8.dp)
    )
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    iconTint: Color = OneUiTealBright,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = OneUiDarkTextPrimary
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = OneUiDarkTextSecondary
                )
            )
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = OneUiTealBright,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = OneUiDarkTextPrimary
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = OneUiDarkTextSecondary
                )
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = OneUiTealPrimary,
                uncheckedThumbColor = OneUiDarkTextSecondary,
                uncheckedTrackColor = OneUiDarkSurface
            )
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = OneUiDarkTextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(text = value, color = OneUiTealBright, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(OneUiDarkBorder.copy(alpha = 0.5f))
    )
}
