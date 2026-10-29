package com.aura.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.backup.Backups
import com.aura.security.AppLock
import com.aura.security.RootDetector
import com.aura.security.SecureWipe
import com.aura.server.RendezvousServerController
import com.aura.settings.AuroraSettings
import com.aura.settings.ThemeMode
import com.aura.settings.ThemePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: AuroraSettings,
    val serverController: RendezvousServerController,
    val appLockManager: AppLock,
    private val secureWipe: SecureWipe,
    private val backupManager: Backups
) : ViewModel() {

    /** Build an encrypted backup blob to write to a user-chosen file. */
    suspend fun buildBackup(passphrase: String): ByteArray = backupManager.export(passphrase.toCharArray())

    /** Restore from a backup blob; on success the app hard-exits so the restored identity loads. */
    fun restoreBackup(bytes: ByteArray, passphrase: String) {
        if (appLockManager.decoyActive.value) return   // decoy: no identity-altering actions
        viewModelScope.launch {
            backupManager.import(bytes, passphrase.toCharArray())
                .onSuccess { secureWipe.exitProcess() }
                .onFailure { _testResult.value = "Restore failed: ${it.message}" }
        }
    }

    val isRooted: Boolean by lazy { RootDetector.isLikelyRooted() }

    val lockEnabledFlow: StateFlow<Boolean> = appLockManager.enabled
    // PIN/lock changes are blocked under decoy so a coercer can't lock out the real owner
    // (change the real PIN) or tear down the lock while holding only the decoy PIN.
    // Outside decoy mode, AppLock itself requires the current PIN for every change.
    fun setPin(newPin: String, currentPin: String? = null): AppLock.LockChangeResult =
        if (appLockManager.decoyActive.value) AppLock.LockChangeResult.WRONG_PIN
        else appLockManager.setPin(newPin, currentPin)

    fun setDecoyPin(newPin: String, currentPin: String? = null): AppLock.LockChangeResult =
        if (appLockManager.decoyActive.value) AppLock.LockChangeResult.WRONG_PIN
        else appLockManager.setDecoyPin(newPin, currentPin)

    fun disableLock(currentPin: String): AppLock.LockChangeResult =
        if (appLockManager.decoyActive.value) AppLock.LockChangeResult.WRONG_PIN
        else appLockManager.disableLock(currentPin)

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
    fun setThemePalette(palette: ThemePalette) = settings.setThemePalette(palette)
    fun setShadowMesh(enabled: Boolean) = settings.setShadowMeshEnabled(enabled)

    // Surfaces backup/restore status to the UI (see restoreBackup).
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult

    fun setDuressWipe(enabled: Boolean) = settings.setDuressWipe(enabled)

    /**
     * Cryptographic erase of everything, then hard-exit. Destroying the DB key and the
     * identity/lock key vaults makes the on-disk ciphertext unrecoverable — far stronger
     * than deleting rows. The app closes; relaunch generates a fresh identity.
     */
    fun clearAllData() {
        if (appLockManager.decoyActive.value) return   // decoy: don't let a coercer wipe the real data
        viewModelScope.launch {
            serverController.stop()
            secureWipe.wipeEverything()
            secureWipe.exitProcess()
        }
    }
}
