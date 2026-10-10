package com.example.core.profile

/**
 * Runtime roles for the application instance.
 * - PERSONAL_CONTROLLER: Running in the user's primary/personal profile (User 0).
 *   Owns all user-facing UI (Vault, Camera, Notes, Cloned Apps overview, Settings).
 * - MANAGED_EXECUTOR: Running inside the Android Enterprise managed Work Profile.
 *   Acts as the Profile Owner executor for system policy operations (installExistingPackage, unclone).
 */
enum class ProfileRole {
    PERSONAL_CONTROLLER,
    MANAGED_EXECUTOR
}

/**
 * High-level semantic state of the Private Workspace.
 */
enum class WorkspaceState {
    NOT_PROVISIONED,
    PROVISIONING,
    READY,
    PAUSED,
    EXECUTOR_UNREACHABLE,
    ERROR
}

/**
 * Granular operation state for an individual package.
 */
enum class PackageOpState {
    AVAILABLE,
    CLONING,
    CLONED,
    FAILED,
    REMOVING
}

/**
 * Per-package operation status with explanatory message.
 */
data class PackageStatusInfo(
    val state: PackageOpState,
    val message: String? = null
)
