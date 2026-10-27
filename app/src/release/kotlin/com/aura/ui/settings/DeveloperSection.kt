package com.aura.ui.settings

import androidx.compose.runtime.Composable

/**
 * Release build of the developer tools: nothing. The real [DeveloperSection] (the
 * rendezvous Server-Mode UI) lives only in `src/debug`, so it is never compiled into a
 * release APK (user rule: no dev code in release). [SettingsScreen] gates the call with
 * `BuildConfig.DEBUG`, so this stub is never reached at runtime either.
 */
@Composable
internal fun DeveloperSection() { /* no developer tools in release */ }
