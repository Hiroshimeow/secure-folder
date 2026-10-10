package com.example.viewmodel

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.SecureFolderApp
import kotlinx.coroutines.Job
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
    val pinLength: Int = 4,
    val isDisguised: Boolean = false,
    val pinSetupPhase: PinSetupPhase = PinSetupPhase.NOT_IN_SETUP,
    val pinInput: String = "",
    val firstEnteredPin: String = "",
    val errorMessage: String? = null,
    val isVerifying: Boolean = false,
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
    private var verificationJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val isSetup = settings.isPinSetup
                _uiState.value = _uiState.value.copy(
                    isLocked = settings.isLocked,
                    isPinSetup = isSetup,
                    pinLength = settings.pinLength,
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

    /**
     * Fast keypad response:
     * 1. Updates UI state immediately without stalling the main thread.
     * 2. In normal unlock flow, triggers asynchronous PBKDF2 verification ONLY when candidate
     *    reaches the exact configured pinLength (e.g. 4 or 6).
     * 3. Prevents duplicate verification runs using cancellation.
     */
    fun onPinDigit(digit: String) {
        if (_uiState.value.lockoutRemainingSeconds > 0) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Tạm khóa do nhập sai nhiều lần. Vui lòng đợi ${_uiState.value.lockoutRemainingSeconds}s."
            )
            return
        }

        if (_uiState.value.isVerifying) {
            // Already verifying candidate, prevent typing race
            return
        }

        val current = _uiState.value.pinInput
        val targetLength = if (_uiState.value.isPinSetup) _uiState.value.pinLength else 6
        if (current.length < targetLength) {
            val updated = current + digit
            _uiState.value = _uiState.value.copy(pinInput = updated, errorMessage = null)

            // If in initial setup flow, do not auto-verify against old pin
            if (!_uiState.value.isPinSetup) {
                return
            }

            // Normal Unlock Flow: verify ONLY when candidate reaches configured pinLength!
            if (updated.length == _uiState.value.pinLength) {
                submitCandidatePin(updated)
            }
        }
    }

    /**
     * Explicit or auto-submit candidate PIN.
     * Cancels any in-flight job, runs PBKDF2 on background dispatcher, and updates UI on completion.
     */
    fun submitCandidatePin(candidate: String = _uiState.value.pinInput) {
        if (candidate.length < 4 || _uiState.value.lockoutRemainingSeconds > 0) {
            return
        }

        verificationJob?.cancel()
        verificationJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isVerifying = true)
            val isCorrect = settingsRepository.verifyPin(candidate)
            _uiState.value = _uiState.value.copy(isVerifying = false)

            if (isCorrect) {
                _uiState.value = _uiState.value.copy(failedAttempts = 0)
                unlockVault()
            } else {
                handleFailedAttempt()
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
                    viewModelScope.launch {
                        _uiState.value = _uiState.value.copy(isVerifying = true)
                        val success = settingsRepository.setPin(input)
                        _uiState.value = _uiState.value.copy(isVerifying = false)
                        if (success) {
                            _uiState.value = _uiState.value.copy(
                                pinSetupPhase = PinSetupPhase.NOT_IN_SETUP,
                                pinInput = "",
                                firstEnteredPin = "",
                                errorMessage = null
                            )
                            unlockVault()
                        }
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
        if (_uiState.value.isVerifying) return
        verificationJob?.cancel()
        val current = _uiState.value.pinInput
        if (current.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(pinInput = current.dropLast(1), errorMessage = null)
        }
    }

    fun onPinClear() {
        if (_uiState.value.isVerifying) return
        verificationJob?.cancel()
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
            verificationJob?.cancel()
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
     * Calculator disguise logic with background async PIN check.
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

                val candidate1 = currentDisplay
                val candidate2 = currentExpr.trim()
                val isCandidate = (candidate1.length in 4..6 && candidate1.all { it.isDigit() }) ||
                        (candidate2.length in 4..6 && candidate2.all { it.isDigit() })

                viewModelScope.launch {
                    val pass1 = if (candidate1.length in 4..6 && candidate1.all { it.isDigit() }) settingsRepository.verifyPin(candidate1) else false
                    val pass2 = if (!pass1 && candidate2.length in 4..6 && candidate2.all { it.isDigit() }) settingsRepository.verifyPin(candidate2) else false

                    if (pass1 || pass2) {
                        _uiState.value = _uiState.value.copy(failedAttempts = 0)
                        unlockVault()
                    } else if (isCandidate && _uiState.value.isPinSetup) {
                        handleFailedAttempt()
                        if (_uiState.value.lockoutRemainingSeconds > 0) {
                            _uiState.value = _uiState.value.copy(calculatorDisplay = "LOCKED")
                        }
                    } else {
                        // Standard math evaluation
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
                val num = currentDisplay.toDoubleOrNull()
                if (num != null) {
                    _uiState.value = _uiState.value.copy(calculatorDisplay = (num / 100.0).toString())
                }
            }
            "." -> {
                if (!currentDisplay.contains(".")) {
                    _uiState.value = _uiState.value.copy(calculatorDisplay = "$currentDisplay.")
                }
            }
            else -> {
                // Number entry
                val newDisplay = if (currentDisplay == "0" || currentDisplay == "Lỗi" || currentDisplay == "LOCKED") key else currentDisplay + key
                _uiState.value = _uiState.value.copy(calculatorDisplay = newDisplay)
            }
        }
    }

    private fun evaluateMath(expression: String): String {
        val tokens = expression.split(" ").filter { it.isNotBlank() }
        if (tokens.size < 3) return tokens.firstOrNull() ?: "0"

        var total = tokens[0].toDoubleOrNull() ?: 0.0
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i]
            val nextVal = tokens.getOrNull(i + 1)?.toDoubleOrNull() ?: 0.0
            total = when (op) {
                "+" -> total + nextVal
                "-" -> total - nextVal
                "×" -> total * nextVal
                "÷" -> if (nextVal != 0.0) total / nextVal else return "Lỗi"
                else -> total
            }
            i += 2
        }
        return if (total % 1.0 == 0.0) total.toLong().toString() else total.toString()
    }

    fun promptBiometrics(activity: FragmentActivity) {
        val biometricManager = BiometricManager.from(activity)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    _uiState.value = _uiState.value.copy(failedAttempts = 0)
                    unlockVault()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    _uiState.value = _uiState.value.copy(errorMessage = "Dấu vân tay không khớp.")
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Mở khóa Thư mục Bảo mật")
            .setSubtitle("Xác thực bằng cảm biến sinh trắc học")
            .setNegativeButtonText("Dùng mã PIN")
            .build()

        prompt.authenticate(promptInfo)
    }
}
