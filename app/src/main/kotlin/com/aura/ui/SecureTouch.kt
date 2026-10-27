package com.aura.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Tapjacking guard for a Compose dialog (FB-5). A Material3 `AlertDialog`/`Dialog` renders in its
 * own window, so the Activity-level `filterTouchesWhenObscured` (set in MainActivity) does not
 * cover it. Call this inside a security-sensitive dialog's content to drop touches that pass
 * through an overlay drawn over the dialog by another app.
 */
@Composable
fun FilterObscuredTouches() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.decorView?.filterTouchesWhenObscured = true
        // Belt-and-suspenders: also guard the content view itself.
        view.filterTouchesWhenObscured = true
    }
}
