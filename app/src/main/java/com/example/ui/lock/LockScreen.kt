package com.example.ui.lock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
fun LockScreen(
    viewModel: LockViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    // Trigger biometrics automatically if configured
    LaunchedEffect(Unit) {
        if (!state.isDisguised && activity != null) {
            viewModel.promptBiometrics(activity)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OneUiDarkBackground)
    ) {
        if (state.isDisguised) {
            CalculatorDisguiseView(viewModel = viewModel)
        } else {
            OneUiPinLockView(
                viewModel = viewModel,
                activity = activity
            )
        }
    }
}

@Composable
private fun OneUiPinLockView(
    viewModel: LockViewModel,
    activity: FragmentActivity?
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Security Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(OneUiTealPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Samsung Knox Security",
                    tint = OneUiTealBright,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Thư mục Bảo mật",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = OneUiDarkTextPrimary,
                    fontSize = 24.sp
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Nhập mã PIN để mở khóa",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = OneUiDarkTextPrimary,
                    fontWeight = FontWeight.Medium
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Mã PIN mặc định: 1234 (Đổi trong Cài đặt)",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = OneUiTealBright,
                    fontSize = 12.sp
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            // PIN Dots Indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pinLength = 4
                for (i in 0 until pinLength) {
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

            // Error message
            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = state.errorMessage ?: "",
                    color = OneUiLockRed,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
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
                listOf("BIO", "0", "DEL")
            )

            for (row in keyRows) {
                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    for (key in row) {
                        when (key) {
                            "BIO" -> {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            activity?.let { viewModel.promptBiometrics(it) }
                                        }
                                        .testTag("biometric_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = "Vân tay",
                                        tint = OneUiTealBright,
                                        modifier = Modifier.size(34.dp)
                                    )
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
            text = "Bảo vệ bởi Knox TEE / Android Enterprise DPC",
            style = MaterialTheme.typography.labelSmall.copy(
                color = OneUiDarkTextSecondary.copy(alpha = 0.6f)
            ),
            modifier = Modifier.padding(bottom = 8.dp)
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
