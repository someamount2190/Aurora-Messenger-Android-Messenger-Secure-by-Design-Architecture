package com.aura.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.R
import com.aura.di.IoDispatcher
import com.aura.network.ServerUrlPolicy
import com.aura.settings.AuroraSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import javax.inject.Inject

@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val settings: AuroraSettings,
    @IoDispatcher private val io: CoroutineDispatcher
) : ViewModel() {

    val serverAddress: StateFlow<String> = settings.serverAddress
    val isCustom: Boolean get() = settings.isCustomServer

    /** One-at-a-time flow: validate → warn → probe/capture cert → confirm trust → applied. */
    sealed interface Step {
        data object Idle : Step
        data class Error(val error: ServerUrlPolicy.ValidationError?) : Step   // null = probe failure
        data class Warn(val url: HttpUrl) : Step
        data object Probing : Step
        data class Confirm(val url: HttpUrl, val cert: ServerUrlPolicy.CapturedCert) : Step
        data object Applied : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step

    /** Keeps the user-facing message for a probe failure (not a validation enum). */
    var probeError: String? = null; private set

    fun begin(raw: String) {
        ServerUrlPolicy.validate(raw).fold(
            onSuccess = { _step.value = Step.Warn(it) },
            onFailure = {
                _step.value = Step.Error((it as? ServerUrlPolicy.PolicyException)?.error)
            }
        )
    }

    fun proceedFromWarning() {
        val url = (_step.value as? Step.Warn)?.url ?: return
        _step.value = Step.Probing
        viewModelScope.launch {
            withContext(io) { ServerUrlPolicy.capturePin(url) }.fold(
                onSuccess = { _step.value = Step.Confirm(url, it) },
                onFailure = { probeError = it.message; _step.value = Step.Error(null) }
            )
        }
    }

    fun confirmTrust() {
        val c = _step.value as? Step.Confirm ?: return
        settings.setCustomServer(c.url.toString().trimEnd('/'), c.cert.spkiPin)
        _step.value = Step.Applied
    }

    fun reset() { settings.clearCustomServer(); _step.value = Step.Applied }
    fun dismiss() { _step.value = Step.Idle }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    onBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel()
) {
    val address by viewModel.serverAddress.collectAsState()
    val step by viewModel.step.collectAsState()
    var draft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.server_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(address, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(if (viewModel.isCustom) R.string.server_badge_custom else R.string.server_badge_default),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (viewModel.isCustom) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.reset() }) {
                            Text(stringResource(R.string.server_reset))
                        }
                    }
                }
            }

            Text(stringResource(R.string.server_field_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.server_field_label)) },
                singleLine = true,
                isError = step is ServerSettingsViewModel.Step.Error,
                supportingText = {
                    (step as? ServerSettingsViewModel.Step.Error)?.let {
                        Text(errorText(it.error, viewModel.probeError), color = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { viewModel.begin(draft) },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.server_apply)) }
        }
    }

    when (val s = step) {
        is ServerSettingsViewModel.Step.Warn -> {
            com.aura.ui.FilterObscuredTouches()   // FB-5: custom-server consent is tapjacking-sensitive
            AlertDialog(
                onDismissRequest = { viewModel.dismiss() },
                title = { Text(stringResource(R.string.server_warn_title)) },
                text = { Text(stringResource(R.string.server_warn_body)) },
                confirmButton = { TextButton(onClick = { viewModel.proceedFromWarning() }) { Text(stringResource(R.string.server_warn_continue)) } },
                dismissButton = { TextButton(onClick = { viewModel.dismiss() }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
        is ServerSettingsViewModel.Step.Probing -> AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.server_probing)) },
            text = { CircularProgressIndicator() },
            confirmButton = {}
        )
        is ServerSettingsViewModel.Step.Confirm -> {
            com.aura.ui.FilterObscuredTouches()   // FB-5: "trust this server" is tapjacking-sensitive
            AlertDialog(
            onDismissRequest = { viewModel.dismiss() },
            title = { Text(stringResource(R.string.server_trust_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.server_trust_body))
                    Spacer(Modifier.height(8.dp))
                    Text(s.cert.subject, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.server_trust_fingerprint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.cert.fingerprint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmTrust() }) { Text(stringResource(R.string.server_trust_confirm)) } },
            dismissButton = { TextButton(onClick = { viewModel.dismiss() }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
        is ServerSettingsViewModel.Step.Applied -> { onBack(); viewModel.dismiss() }
        else -> Unit
    }
}

@Composable
private fun errorText(error: ServerUrlPolicy.ValidationError?, probeError: String?): String = when (error) {
    ServerUrlPolicy.ValidationError.Blank -> stringResource(R.string.server_err_blank)
    ServerUrlPolicy.ValidationError.NotHttps -> stringResource(R.string.server_err_not_https)
    ServerUrlPolicy.ValidationError.Malformed -> stringResource(R.string.server_err_malformed)
    ServerUrlPolicy.ValidationError.HasCredentials -> stringResource(R.string.server_err_credentials)
    null -> probeError ?: stringResource(R.string.server_err_unreachable)
}
