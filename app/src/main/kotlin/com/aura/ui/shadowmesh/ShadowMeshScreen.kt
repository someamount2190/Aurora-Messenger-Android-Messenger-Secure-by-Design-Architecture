package com.aura.ui.shadowmesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aura.R
import com.aura.settings.AuroraSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class ShadowMeshViewModel @Inject constructor(
    private val settings: AuroraSettings
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.shadowMeshEnabled
    fun setEnabled(value: Boolean) = settings.setShadowMeshEnabled(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShadowMeshScreen(
    onBack: () -> Unit,
    viewModel: ShadowMeshViewModel = hiltViewModel()
) {
    val enabled by viewModel.enabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sm_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (enabled) {
                        Text(
                            stringResource(R.string.sm_joined),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.setEnabled(false); onBack() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.sm_leave)) }
                    } else {
                        Button(
                            onClick = { viewModel.setEnabled(true); onBack() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.sm_join)) }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.sm_not_now)) }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(
                stringResource(R.string.sm_heading),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))

            Para(stringResource(R.string.sm_p1))
            Para(stringResource(R.string.sm_p2))
            Para(stringResource(R.string.sm_p3))

            Heading(stringResource(R.string.sm_h1))
            Para(stringResource(R.string.sm_p4))
            Para(stringResource(R.string.sm_p5))

            Heading(stringResource(R.string.sm_h2))
            Para(stringResource(R.string.sm_p6))

            Heading(stringResource(R.string.sm_h3))
            Para(stringResource(R.string.sm_p7))

            Heading(stringResource(R.string.sm_h4))
            Para(stringResource(R.string.sm_p8))
            Para(stringResource(R.string.sm_p9))

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.sm_closing),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Para(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}
