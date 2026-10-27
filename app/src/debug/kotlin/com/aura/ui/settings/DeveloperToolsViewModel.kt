package com.aura.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.crypto.toHex
import com.aura.db.AuroraDatabase
import com.aura.identity.IdentityStore
import com.aura.network.Rendezvous
import com.aura.server.RendezvousServerController
import com.aura.settings.AuroraSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the developer-only [DeveloperSection]. Lives in the `debug` source set, so it
 * and the in-app rendezvous server it drives are never compiled into release builds
 * (user rule: no dev code in release). The production [SettingsViewModel] no longer
 * carries any of this.
 */
@HiltViewModel
class DeveloperToolsViewModel @Inject constructor(
    val settings: AuroraSettings,
    val serverController: RendezvousServerController,
    private val rendezvousClient: Rendezvous,
    private val identityManager: IdentityStore,
    private val database: AuroraDatabase
) : ViewModel() {

    val meshPeerCount = database.meshPeerDao().observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult

    /** Hide the developer tools again (clears the revealed flag). */
    fun setDeveloperMode(enabled: Boolean) = settings.setDeveloperMode(enabled)

    fun setServerMode(enabled: Boolean) {
        settings.setServerMode(enabled)
        if (enabled) serverController.start() else serverController.stop()
    }

    /** Developer tool: sign a check-in and POST it to the configured server. */
    fun testCheckIn() {
        _testResult.value = "Checking in…"
        viewModelScope.launch {
            val identity = identityManager.getOrCreate()
            val result = rendezvousClient.checkIn(settings.serverAddress.value, identity)
            _testResult.value = result.fold(
                onSuccess = { "Check-in OK: $it" },
                onFailure = { "Check-in failed: ${it.message}" }
            )
        }
    }

    /** Developer tool: look up our own nodeId and verify the real IP among 10 candidates. */
    fun testFindSelf() {
        _testResult.value = "Looking up…"
        viewModelScope.launch {
            val identity = identityManager.getOrCreate()
            val nodeIdHex = identity.nodeId.toHex()
            val result = rendezvousClient.find(settings.serverAddress.value, nodeIdHex)
            _testResult.value = result.fold(
                onSuccess = { candidates ->
                    val real = rendezvousClient.verifyCandidates(
                        nodeIdHex,
                        identity.publicPart.signingPublicKey.ed25519PublicKey,
                        identity.publicPart.signingPublicKey.dilithiumPublicKey,
                        candidates
                    )
                    "Got ${candidates.size} candidates. " +
                        if (real != null) "Verified real IP: ${real.ip}:${real.port}"
                        else "No candidate verified (not checked in yet?)"
                },
                onFailure = { "Find failed: ${it.message}" }
            )
        }
    }
}
