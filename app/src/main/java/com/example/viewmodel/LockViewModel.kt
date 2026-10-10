package com.example.viewmodel

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.SecureFolderApp
import com.example.data.model.SecureVaultSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LockUiState(
    val isLocked: Boolean = true,
    val isDisguised: Boolean = false,
    val pinInput: String = "",
    val errorMessage: String? = null,
    val calculatorDisplay: String = "0",
    val calculatorExpression: String = "",
    val biometricAvailable: Boolean = false
)

class LockViewModel : ViewModel() {

    private val settingsRepository = SecureFolderApp.instance.settingsRepository
    private val profileManager = SecureFolderApp.instance.profileManager

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(
                    isLocked = settings.isLocked,
                    isDisguised = settings.isDisguisedAsCalculator
                )
            }
        }
    }

    fun onPinDigit(digit: String) {
        val current = _uiState.value.pinInput
        if (current.length < 6) {
            val updated = current + digit
            _uiState.value = _uiState.value.copy(pinInput = updated, errorMessage = null)
            if (updated.length >= 4) {
                // Check if PIN matches
                if (settingsRepository.verifyPin(updated)) {
                    unlockVault()
                } else if (updated.length == 4 && !settingsRepository.verifyPin(updated)) {
                    // Let user finish or if 4 digits mismatch:
                    // If pin length is 4 in settings, show error after short check
                    val expectedLen = settingsRepository.settings.value.pinCode.length
                    if (updated.length == expectedLen) {
                        _uiState.value = _uiState.value.copy(
                            pinInput = "",
                            errorMessage = "Mã PIN không chính xác. Vui lòng thử lại."
                        )
                    }
                }
            }
        }
    }

    fun onPinDelete() {
        val current = _uiState.value.pinInput
        if (current.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(pinInput = current.dropLast(1), errorMessage = null)
        }
    }

    fun onPinClear() {
        _uiState.value = _uiState.value.copy(pinInput = "", errorMessage = null)
    }

    fun unlockVault() {
        viewModelScope.launch {
            // Start fresh authenticated session
            SecureFolderApp.instance.sessionManager.startSession()
            // Unfreeze Managed Profile and background tasks
            profileManager.setProfileLocked(false)
            settingsRepository.setLocked(false)
            _uiState.value = _uiState.value.copy(
                isLocked = false,
                pinInput = "",
                errorMessage = null
            )
        }
    }

    fun lockVault() {
        viewModelScope.launch {
            // Revoke and invalidate session token, purging RAM plaintext
            SecureFolderApp.instance.sessionManager.invalidateSession()
            // Freeze profile processes, suppress notifications
            profileManager.setProfileLocked(true)
            settingsRepository.setLocked(true)
            _uiState.value = _uiState.value.copy(
                isLocked = true,
                pinInput = "",
                calculatorDisplay = "0",
                calculatorExpression = "",
                errorMessage = null
            )
        }
    }

    /**
     * Calculator disguise logic:
     * Fully works as a real calculator, but if the secret PIN followed by '=' is entered,
     * it unlocks the Samsung Secure Folder!
     */
    fun onCalculatorKey(key: String) {
        val currentDisplay = _uiState.value.calculatorDisplay
        val currentExpr = _uiState.value.calculatorExpression

        when (key) {
            "C" -> {
                _uiState.value = _uiState.value.copy(calculatorDisplay = "0", calculatorExpression = "")
            }
            "=" -> {
                // Check if the current expression or display is the secret PIN!
                if (settingsRepository.verifyPin(currentDisplay) || settingsRepository.verifyPin(currentExpr)) {
                    unlockVault()
                    return
                }
                // Otherwise evaluate simple math
                try {
                    val result = evaluateMath(currentExpr + currentDisplay)
                    _uiState.value = _uiState.value.copy(
                        calculatorDisplay = result,
                        calculatorExpression = ""
                    )
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(calculatorDisplay = "Lỗi", calculatorExpression = "")
                }
            }
            "+", "-", "×", "÷" -> {
                _uiState.value = _uiState.value.copy(
                    calculatorExpression = "$currentDisplay $key ",
                    calculatorDisplay = "0"
                )
            }
            "+/-" -> {
                if (currentDisplay != "0") {
                    val toggled = if (currentDisplay.startsWith("-")) currentDisplay.substring(1) else "-$currentDisplay"
                    _uiState.value = _uiState.value.copy(calculatorDisplay = toggled)
                }
            }
            "%" -> {
                val num = currentDisplay.toDoubleOrNull() ?: 0.0
                _uiState.value = _uiState.value.copy(calculatorDisplay = (num / 100.0).toString())
            }
            else -> {
                // Digit or decimal
                val updated = if (currentDisplay == "0" && key != ".") key else currentDisplay + key
                _uiState.value = _uiState.value.copy(calculatorDisplay = updated)
            }
        }
    }

    private fun evaluateMath(expr: String): String {
        val parts = expr.trim().split(" ")
        if (parts.size >= 3) {
            val a = parts[0].toDoubleOrNull() ?: 0.0
            val op = parts[1]
            val b = parts[2].toDoubleOrNull() ?: 0.0
            val res = when (op) {
                "+" -> a + b
                "-" -> a - b
                "×" -> a * b
                "÷" -> if (b != 0.0) a / b else return "Lỗi chia cho 0"
                else -> a
            }
            return if (res % 1.0 == 0.0) res.toLong().toString() else "%.4f".format(res).trimEnd('0').trimEnd('.')
        }
        return expr
    }

    /**
     * Prompts Android Biometrics (Strong Class 3 / Fingerprint).
     */
    fun promptBiometrics(activity: FragmentActivity) {
        val biometricManager = BiometricManager.from(activity)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                unlockVault()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    _uiState.value = _uiState.value.copy(errorMessage = errString.toString())
                }
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Mở khóa Thư mục Bảo mật")
            .setSubtitle("Xác thực vân tay để truy cập không gian riêng tư")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo)
    }
}
