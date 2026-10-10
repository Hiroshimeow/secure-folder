package com.example.viewmodel

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.SecureFolderApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PinSetupPhase {
    NOT_IN_SETUP,
    ENTER_NEW_PIN,
    CONFIRM_NEW_PIN
}

data class LockUiState(
    val isLocked: Boolean = true,
    val isPinSetup: Boolean = false,
    val isDisguised: Boolean = false,
    val pinSetupPhase: PinSetupPhase = PinSetupPhase.NOT_IN_SETUP,
    val pinInput: String = "",
    val firstEnteredPin: String = "",
    val errorMessage: String? = null,
    val calculatorDisplay: String = "0",
    val calculatorExpression: String = "",
    val failedAttempts: Int = 0,
    val lockoutRemainingSeconds: Int = 0,
    val useBiometrics: Boolean = true
)

class LockViewModel : ViewModel() {

    private val settingsRepository = SecureFolderApp.instance.settingsRepository
    private val profileManager = SecureFolderApp.instance.profileManager

    private val _uiState = MutableStateFlow(LockUiState())
    val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

    private var lockoutUntilMillis: Long = 0L

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val isSetup = settings.isPinSetup
                _uiState.value = _uiState.value.copy(
                    isLocked = settings.isLocked,
                    isPinSetup = isSetup,
                    isDisguised = settings.isDisguisedAsCalculator,
                    useBiometrics = settings.useBiometrics,
                    pinSetupPhase = if (!isSetup) PinSetupPhase.ENTER_NEW_PIN else PinSetupPhase.NOT_IN_SETUP
                )
            }
        }

        // Lockout countdown timer
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val remaining = ((lockoutUntilMillis - System.currentTimeMillis()) / 1000).toInt()
                if (remaining > 0) {
                    _uiState.value = _uiState.value.copy(lockoutRemainingSeconds = remaining)
                } else if (_uiState.value.lockoutRemainingSeconds > 0) {
                    _uiState.value = _uiState.value.copy(lockoutRemainingSeconds = 0, errorMessage = null)
                }
            }
        }
    }

    fun onPinDigit(digit: String) {
        if (_uiState.value.lockoutRemainingSeconds > 0) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Tạm khóa do nhập sai nhiều lần. Vui lòng đợi ${_uiState.value.lockoutRemainingSeconds}s."
            )
            return
        }

        val current = _uiState.value.pinInput
        if (current.length < 6) {
            val updated = current + digit
            _uiState.value = _uiState.value.copy(pinInput = updated, errorMessage = null)

            // If in initial setup flow
            if (!_uiState.value.isPinSetup) {
                return
            }

            // Normal Unlock Flow:
            if (updated.length >= 4) {
                if (settingsRepository.verifyPin(updated)) {
                    _uiState.value = _uiState.value.copy(failedAttempts = 0)
                    unlockVault()
                } else if (updated.length == 6) {
                    // Maximum 6 digits reached and incorrect
                    handleFailedAttempt()
                }
            }
        }
    }

    fun submitPinSetup() {
        val input = _uiState.value.pinInput
        if (input.length < 4) {
            _uiState.value = _uiState.value.copy(errorMessage = "Mã PIN phải có từ 4 đến 6 chữ số.")
            return
        }

        when (_uiState.value.pinSetupPhase) {
            PinSetupPhase.ENTER_NEW_PIN -> {
                _uiState.value = _uiState.value.copy(
                    pinSetupPhase = PinSetupPhase.CONFIRM_NEW_PIN,
                    firstEnteredPin = input,
                    pinInput = "",
                    errorMessage = null
                )
            }
            PinSetupPhase.CONFIRM_NEW_PIN -> {
                if (input == _uiState.value.firstEnteredPin) {
                    val success = settingsRepository.setPin(input)
                    if (success) {
                        _uiState.value = _uiState.value.copy(
                            pinSetupPhase = PinSetupPhase.NOT_IN_SETUP,
                            pinInput = "",
                            firstEnteredPin = "",
                            errorMessage = null
                        )
                        unlockVault()
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        pinSetupPhase = PinSetupPhase.ENTER_NEW_PIN,
                        pinInput = "",
                        firstEnteredPin = "",
                        errorMessage = "Mã xác nhận không khớp. Vui lòng thiết lập lại từ đầu."
                    )
                }
            }
            else -> {}
        }
    }

    fun onPinDelete() {
        val current = _uiState.value.pinInput
        // If user already typed >= 4 digits (a full candidate PIN that failed to unlock) and is deleting,
        // treat this abandoned incorrect sequence as a failed attempt to prevent silent brute-forcing!
        if (_uiState.value.isPinSetup && current.length >= 4) {
            handleFailedAttempt()
            return
        }
        if (current.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(pinInput = current.dropLast(1), errorMessage = null)
        }
    }

    fun onPinClear() {
        val current = _uiState.value.pinInput
        if (_uiState.value.isPinSetup && current.length >= 4) {
            handleFailedAttempt()
            return
        }
        _uiState.value = _uiState.value.copy(pinInput = "", errorMessage = null)
    }

    private fun handleFailedAttempt() {
        val newFailed = _uiState.value.failedAttempts + 1
        val lockoutSeconds = when {
            newFailed >= 15 -> 300
            newFailed >= 10 -> 60
            newFailed >= 5 -> 30
            else -> 0
        }

        if (lockoutSeconds > 0) {
            lockoutUntilMillis = System.currentTimeMillis() + (lockoutSeconds * 1000L)
            _uiState.value = _uiState.value.copy(
                pinInput = "",
                failedAttempts = newFailed,
                lockoutRemainingSeconds = lockoutSeconds,
                errorMessage = "Nhập sai $newFailed lần. Tạm khóa trong ${lockoutSeconds}s."
            )
        } else {
            _uiState.value = _uiState.value.copy(
                pinInput = "",
                failedAttempts = newFailed,
                errorMessage = "Mã PIN không chính xác (Còn ${5 - newFailed} lần thử)."
            )
        }
    }

    fun unlockVault() {
        viewModelScope.launch {
            // Start fresh authenticated session
            SecureFolderApp.instance.sessionManager.startSession()
            // Unfreeze profile
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
     */
    fun onCalculatorKey(key: String) {
        val currentDisplay = _uiState.value.calculatorDisplay
        val currentExpr = _uiState.value.calculatorExpression

        when (key) {
            "C" -> {
                _uiState.value = _uiState.value.copy(calculatorDisplay = "0", calculatorExpression = "")
            }
            "=" -> {
                if (_uiState.value.lockoutRemainingSeconds > 0) {
                    _uiState.value = _uiState.value.copy(calculatorDisplay = "LOCKED")
                    return
                }

                val isPinCandidate = (currentDisplay.length in 4..6 && currentDisplay.all { it.isDigit() }) ||
                        (currentExpr.trim().length in 4..6 && currentExpr.trim().all { it.isDigit() })

                // Check if the current expression or display is the secret PIN!
                if (settingsRepository.verifyPin(currentDisplay) || settingsRepository.verifyPin(currentExpr.trim())) {
                    _uiState.value = _uiState.value.copy(failedAttempts = 0)
                    unlockVault()
                    return
                } else if (isPinCandidate && _uiState.value.isPinSetup) {
                    // Candidate PIN attempt failed in calculator mode: track failed attempt
                    handleFailedAttempt()
                    if (_uiState.value.lockoutRemainingSeconds > 0) {
                        _uiState.value = _uiState.value.copy(calculatorDisplay = "LOCKED")
                        return
                    }
                }
                // Otherwise evaluate math
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
     * Prompts Android Biometrics only if enabled in Settings (P1.05 fix).
     */
    fun promptBiometrics(activity: FragmentActivity) {
        if (!_uiState.value.useBiometrics || !_uiState.value.isPinSetup) {
            return
        }

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
