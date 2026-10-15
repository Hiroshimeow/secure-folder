package com.example.ui.lock

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.ui.theme.OneUiDarkBackground
import com.example.ui.theme.OneUiDarkCard
import com.example.ui.theme.OneUiDarkTextPrimary
import com.example.ui.theme.OneUiDarkTextSecondary
import com.example.ui.theme.OneUiLockRed
import com.example.ui.theme.OneUiTealBright
import com.example.ui.theme.OneUiTealPrimary
import com.example.viewmodel.LockViewModel
import com.example.viewmodel.PinSetupPhase

@Composable
fun LockScreen(
    viewModel: LockViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    // Trigger biometrics only if enabled, vault is setup, and not disguised (P1.05 fix)
    LaunchedEffect(state.isPinSetup, state.useBiometrics, state.isSecuringWorkspace) {
        if (!state.isSecuringWorkspace && !state.isDisguised && state.isPinSetup && state.useBiometrics && activity != null) {
            viewModel.promptBiometrics(activity)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OneUiDarkBackground)
    ) {
        when {
            state.isSecuringWorkspace -> SecuringWorkspaceView()
            state.isDisguised && state.isPinSetup -> CalculatorDisguiseView(viewModel = viewModel)
            else -> OneUiPinLockView(
                viewModel = viewModel,
                activity = activity
            )
        }
    }
}

@Composable
private fun SecuringWorkspaceView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = OneUiTealBright)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Đang khóa không gian riêng tư…",
            color = OneUiDarkTextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Đang xác minh ứng dụng bí mật đã được ẩn và tạm dừng.",
            color = OneUiDarkTextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OneUiPinLockView(
    viewModel: LockViewModel,
    activity: FragmentActivity?
) {
    val state by viewModel.uiState.collectAsState()

    val headerTitle = if (!state.isPinSetup) {
        if (state.pinSetupPhase == PinSetupPhase.CONFIRM_NEW_PIN) "Xác nhận mã PIN" else "Thiết lập mã PIN Bảo mật"
    } else {
        "Thư mục Bảo mật"
    }

    val headerSubtitle = if (!state.isPinSetup) {
        if (state.pinSetupPhase == PinSetupPhase.CONFIRM_NEW_PIN) "Nhập lại mã PIN để kích hoạt bảo vệ" else "Nhập mã PIN mới (4 đến 6 chữ số)"
    } else {
        "Nhập mã PIN để mở khóa"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Security Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(OneUiTealPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state.lockoutRemainingSeconds > 0) Icons.Default.LockClock else Icons.Default.Security,
                    contentDescription = null,
                    tint = if (state.lockoutRemainingSeconds > 0) OneUiLockRed else OneUiTealBright,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = headerTitle,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = OneUiDarkTextPrimary,
                    fontSize = 22.sp
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = headerSubtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = OneUiDarkTextSecondary,
                    textAlign = TextAlign.Center
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // PIN Dots Indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val totalDots = if (state.isPinSetup) state.pinLength else maxOf(4, state.pinInput.length)
                for (i in 0 until totalDots) {
                    val isFilled = i < state.pinInput.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (isFilled) OneUiTealBright else OneUiDarkCard
                            )
                    )
                }
            }

            // Error or Lockout Message
            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = state.errorMessage ?: "",
                    color = OneUiLockRed,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.Center
                )
            }
        }

        // Action button during PIN setup
        if (!state.isPinSetup && state.pinInput.length >= 4) {
            Button(
                onClick = { viewModel.submitPinSetup() },
                colors = ButtonDefaults.buttonColors(containerColor = OneUiTealPrimary),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(48.dp)
                    .testTag("submit_pin_setup_button")
            ) {
                Text(
                    text = if (state.pinSetupPhase == PinSetupPhase.CONFIRM_NEW_PIN) "Xác nhận & Mở khóa" else "Tiếp tục",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.size(8.dp))
                Icon(
                    imageVector = if (state.pinSetupPhase == PinSetupPhase.CONFIRM_NEW_PIN) Icons.Default.Check else Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }

        // Numeric Keypad
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val keyRows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("ACTION", "0", "DEL")
            )

            for (row in keyRows) {
                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (key in row) {
                        when (key) {
                            "ACTION" -> {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            if (state.isPinSetup && state.pinInput.length >= 4) {
                                                viewModel.submitCandidatePin()
                                            } else if (state.isPinSetup && state.useBiometrics) {
                                                activity?.let { viewModel.promptBiometrics(it) }
                                            } else if (!state.isPinSetup && state.pinInput.length >= 4) {
                                                viewModel.submitPinSetup()
                                            }
                                        }
                                        .testTag("action_key_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (state.isPinSetup && state.pinInput.length >= 4) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Xác nhận",
                                            tint = OneUiTealBright,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    } else if (state.isPinSetup && state.useBiometrics) {
                                        Icon(
                                            imageVector = Icons.Default.Fingerprint,
                                            contentDescription = "Vân tay",
                                            tint = OneUiTealBright,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    } else if (!state.isPinSetup && state.pinInput.length >= 4) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Hoàn tất",
                                            tint = OneUiTealBright,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }
                            "DEL" -> {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .clickable { viewModel.onPinDelete() }
                                        .testTag("pin_delete_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Backspace,
                                        contentDescription = "Xóa",
                                        tint = OneUiDarkTextSecondary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(OneUiDarkCard)
                                        .clickable { viewModel.onPinDigit(key) }
                                        .testTag("pin_key_$key"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            color = OneUiDarkTextPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 24.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom Footer
        Text(
            text = "Xác thực Salted SHA-256 / Android Enterprise DPC",
            style = MaterialTheme.typography.labelSmall.copy(
                color = OneUiDarkTextSecondary.copy(alpha = 0.6f)
            ),
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

@Composable
private fun CalculatorDisguiseView(
    viewModel: LockViewModel
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Calculator Screen Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End
        ) {
            if (state.calculatorExpression.isNotEmpty()) {
                Text(
                    text = state.calculatorExpression,
                    color = OneUiDarkTextSecondary,
                    fontSize = 24.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = state.calculatorDisplay,
                color = OneUiDarkTextPrimary,
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("calculator_display")
            )
        }

        // Keypad Grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val rows = listOf(
                listOf("C", "+/-", "%", "÷"),
                listOf("7", "8", "9", "×"),
                listOf("4", "5", "6", "-"),
                listOf("1", "2", "3", "+"),
                listOf("0", ".", "=")
            )

            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (key in row) {
                        val isOperator = key in listOf("÷", "×", "-", "+", "=")
                        val isFunction = key in listOf("C", "+/-", "%")
                        val weight = if (key == "0") 2f else 1f

                        Box(
                            modifier = Modifier
                                .weight(weight)
                                .height(68.dp)
                                .clip(RoundedCornerShape(34.dp))
                                .background(
                                    when {
                                        key == "=" -> OneUiTealPrimary
                                        isOperator -> Color(0xFF2C3E50)
                                        isFunction -> Color(0xFF34495E)
                                        else -> OneUiDarkCard
                                    }
                                )
                                .clickable { viewModel.onCalculatorKey(key) }
                                .testTag("calc_key_$key"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = key,
                                color = if (key == "=") Color.White else OneUiDarkTextPrimary,
                                fontSize = 26.sp,
                                fontWeight = if (isOperator) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}
