package com.aura.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aura.R
import com.aura.settings.AppLanguage
import com.aura.settings.AppLocale
import com.aura.settings.DisappearingTimer
import com.aura.settings.PeerIpExposurePolicy
import com.aura.settings.ThemeMode
import com.aura.settings.ThemePalette

// Section composables for SettingsScreen. Each owns its own state subscription
// (collectAsState), mirroring DeveloperSection; the parent screen wires the
// dialogs/launchers via the callbacks below.

@Composable
internal fun AppearanceSection(viewModel: SettingsViewModel) {
    val themeMode by viewModel.settings.themeMode.collectAsState()
    val themePalette by viewModel.settings.themePalette.collectAsState()
    SectionTitle(stringResource(R.string.settings_appearance))
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_theme_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            ThemeMode.entries.forEach { mode ->
                OptionRow(
                    label = stringResource(mode.labelRes),
                    selected = themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_palette), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_palette_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            ThemePalette.entries.forEach { palette ->
                OptionRow(
                    label = stringResource(palette.labelRes),
                    selected = themePalette == palette,
                    onClick = { viewModel.setThemePalette(palette) }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            // Changing the language recreates the Activity, which re-reads the selection.
            val context = LocalContext.current
            val language = remember { AppLocale.current(context) }
            AppLanguage.entries.forEach { lang ->
                OptionRow(
                    label = stringResource(lang.labelRes),
                    selected = language == lang,
                    onClick = {
                        if (lang != language) context.findActivity()?.let { AppLocale.set(it, lang) }
                    }
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun PrivacySection(viewModel: SettingsViewModel) {
    val disappearing by viewModel.settings.disappearingTimer.collectAsState()
    val ipPolicy by viewModel.settings.peerIpExposurePolicy.collectAsState()
    SectionTitle(stringResource(R.string.settings_privacy))
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_disappearing), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_disappearing_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            DisappearingTimer.entries.forEach { timer ->
                OptionRow(
                    label = stringResource(timer.labelRes),
                    selected = disappearing == timer,
                    onClick = { viewModel.settings.setDisappearingTimer(timer) }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.settings_ip_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_ip_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            PeerIpExposurePolicy.entries.forEach { policy ->
                OptionRow(
                    label = stringResource(policy.labelRes),
                    selected = ipPolicy == policy,
                    onClick = { viewModel.settings.setPeerIpExposurePolicy(policy) }
                )
            }
        }
    }
}

@Composable
internal fun SecuritySection(
    viewModel: SettingsViewModel,
    onSetPin: () -> Unit,
    onSetDecoy: () -> Unit
) {
    val duressWipe by viewModel.settings.duressWipe.collectAsState()
    SectionTitle(stringResource(R.string.settings_security))
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (viewModel.isRooted) {
                Text(
                    stringResource(R.string.settings_rooted_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                stringResource(R.string.settings_lock_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSetPin, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (viewModel.lockEnabled()) R.string.settings_change_pin else R.string.settings_set_pin))
                }
                OutlinedButton(onClick = onSetDecoy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_decoy_pin))
                }
            }
            if (viewModel.lockEnabled()) {
                TextButton(onClick = { viewModel.disableLock() }) { Text(stringResource(R.string.settings_disable_lock)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_duress),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = duressWipe, onCheckedChange = viewModel::setDuressWipe)
            }
            Text(
                stringResource(R.string.settings_duress_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun NetworkSection(
    viewModel: SettingsViewModel,
    onOpenShadowMesh: () -> Unit,
    onOpenServer: () -> Unit = {}
) {
    val shadowMesh by viewModel.settings.shadowMeshEnabled.collectAsState()
    val serverAddress by viewModel.settings.serverAddress.collectAsState()
    SectionTitle(stringResource(R.string.settings_network))
    Card {
        Column(Modifier.padding(16.dp)) {
            // Rendezvous server: default, or the user's own (HTTPS-only, trust-on-first-use).
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenServer),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            if (serverAddress == com.aura.settings.AuroraSettings.DEFAULT_SERVER_ADDRESS)
                                R.string.server_badge_default else R.string.server_badge_custom
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_shadowmesh), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_shadowmesh_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Turning ON routes through the opt-in screen (Join there);
                // turning OFF is immediate, no friction to leave.
                Switch(
                    checked = shadowMesh,
                    onCheckedChange = { want ->
                        if (want) onOpenShadowMesh() else viewModel.setShadowMesh(false)
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onOpenShadowMesh,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text(stringResource(R.string.settings_shadowmesh_learn)) }
        }
    }
}

@Composable
internal fun AboutLegalSection(onOpenLegal: (String) -> Unit) {
    SectionTitle(stringResource(R.string.settings_about_legal))
    Card {
        Column(Modifier.padding(vertical = 4.dp)) {
            NavRow(stringResource(R.string.legal_terms)) { onOpenLegal("terms") }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            NavRow(stringResource(R.string.legal_privacy)) { onOpenLegal("privacy") }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.settings_contact), style = MaterialTheme.typography.bodyLarge)
                Text(
                    com.aura.legal.LegalDocs.CONTACT_EMAIL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun DataSection(
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onClear: () -> Unit
) {
    SectionTitle(stringResource(R.string.settings_data))
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_export_backup))
            }
            OutlinedButton(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_restore_backup))
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_clear_data), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun PinDialog(
    title: String,
    subtitle: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text(stringResource(R.string.pin_label)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = pin.length >= 4) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}
