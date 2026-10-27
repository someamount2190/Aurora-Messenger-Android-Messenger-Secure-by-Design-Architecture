package com.aura.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.server.RendezvousServerController

/**
 * Network/rendezvous developer tools — not part of the normal user experience, and
 * not compiled into release builds. Lives in the `debug` source set and is backed by
 * [DeveloperToolsViewModel]; [SettingsScreen] calls the no-op release twin instead.
 * Revealed by tapping the version footer seven times; hidden again from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeveloperSection() {
    val viewModel: DeveloperToolsViewModel = hiltViewModel()
    val serverMode by viewModel.settings.serverMode.collectAsState()
    val serverAddress by viewModel.settings.serverAddress.collectAsState()
    val advertisedAddress by viewModel.settings.advertisedAddress.collectAsState()
    val serverStatus by viewModel.serverController.status.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val meshPeerCount by viewModel.meshPeerCount.collectAsState()

    var addressDraft by remember(serverAddress) { mutableStateOf(serverAddress) }
    var advertisedDraft by remember(advertisedAddress) { mutableStateOf(advertisedAddress) }

    SectionTitle("Developer options")
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Server Mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Run the rendezvous server on this device (port 8080)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = serverMode, onCheckedChange = viewModel::setServerMode)
            }

            when (val s = serverStatus) {
                is RendezvousServerController.Status.Running -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Running at ${s.localIp}:${s.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                is RendezvousServerController.Status.Error -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Error: ${s.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = addressDraft,
                onValueChange = { addressDraft = it },
                label = { Text("Server address (client mode)") },
                supportingText = { Text("10.0.2.2 reaches the host machine from an emulator") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (addressDraft != serverAddress) {
                TextButton(onClick = { viewModel.settings.setServerAddress(addressDraft) }) {
                    Text("Save address")
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = advertisedDraft,
                onValueChange = { advertisedDraft = it },
                label = { Text("Advertised address (ip:port, optional)") },
                supportingText = { Text("Override what peers are told to connect to (NAT / emulator testing). Mesh peers learned: $meshPeerCount") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (advertisedDraft != advertisedAddress) {
                TextButton(onClick = { viewModel.settings.setAdvertisedAddress(advertisedDraft) }) {
                    Text("Save advertised address")
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::testCheckIn, modifier = Modifier.weight(1f)) {
                    Text("Check in")
                }
                OutlinedButton(onClick = viewModel::testFindSelf, modifier = Modifier.weight(1f)) {
                    Text("Find myself")
                }
            }
            testResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.setDeveloperMode(false) }) {
                Text("Hide developer options")
            }
        }
    }
}
