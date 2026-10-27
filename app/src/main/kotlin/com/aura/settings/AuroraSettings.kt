package com.aura.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.aura.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-secret app settings. Secret material (identity keys) lives in
 * [com.aura.identity.IdentityStore], never here.
 */
@Singleton
class AuroraSettings @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("aura_settings", Context.MODE_PRIVATE)

    private val _serverMode = MutableStateFlow(prefs.getBoolean(KEY_SERVER_MODE, false))
    /** Phase 0: when true this device runs the in-app rendezvous server. */
    val serverMode: StateFlow<Boolean> = _serverMode

    private val _serverAddress = MutableStateFlow(
        prefs.getString(KEY_SERVER_ADDRESS, DEFAULT_SERVER_ADDRESS) ?: DEFAULT_SERVER_ADDRESS
    )
    /** Rendezvous server base URL used when this device is a client. */
    val serverAddress: StateFlow<String> = _serverAddress

    /** True when the user has pointed at a server other than the bundled default. */
    val isCustomServer: Boolean get() = _serverAddress.value != DEFAULT_SERVER_ADDRESS

    private val _serverPin = MutableStateFlow(prefs.getString(KEY_SERVER_PIN, "") ?: "")
    /**
     * Trust-on-first-use SPKI pin (OkHttp `sha256/…`) captured for a custom HTTPS server,
     * so a later rogue CA can't transparently intercept it. Blank for the pinned default
     * server (which carries its own hard-coded pin in [com.aura.network.RendezvousClient]).
     */
    val serverPin: StateFlow<String> = _serverPin

    private val _advertisedAddress = MutableStateFlow(prefs.getString(KEY_ADVERTISED, "") ?: "")
    /**
     * Optional "ip:port" override advertised in rendezvous check-ins instead of
     * the auto-detected address. Needed when peers can't reach the detected IP
     * directly — NAT'd deployments, and the two-emulator test setup where each
     * emulator reaches the other through adb forward/reverse port plumbing.
     */
    val advertisedAddress: StateFlow<String> = _advertisedAddress

    private val _disappearingTimer = MutableStateFlow(
        DisappearingTimer.fromKey(prefs.getString(KEY_DISAPPEARING, null))
    )
    val disappearingTimer: StateFlow<DisappearingTimer> = _disappearingTimer

    private val _themeMode = MutableStateFlow(ThemeMode.fromKey(prefs.getString(KEY_THEME, null)))
    /** Light / dark / follow-system appearance preference. */
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _themePalette = MutableStateFlow(ThemePalette.fromKey(prefs.getString(KEY_PALETTE, null)))
    /** Colour palette: the current Aurora look, or the classic "Cherish" plum theme. */
    val themePalette: StateFlow<ThemePalette> = _themePalette

    private val _developerMode = MutableStateFlow(prefs.getBoolean(KEY_DEVELOPER, false))
    /** When true, the rendezvous/network developer tools are shown in Settings. */
    val developerMode: StateFlow<Boolean> = _developerMode

    private val _blockedNodes = MutableStateFlow(
        prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()
    )
    /** Node-ids the user has blocked at pairing time (rejected / repeated wrong code). */
    val blockedNodes: StateFlow<Set<String>> = _blockedNodes

    fun isBlocked(nodeIdHex: String): Boolean = _blockedNodes.value.contains(nodeIdHex)

    fun blockNode(nodeIdHex: String) {
        val next = _blockedNodes.value + nodeIdHex
        prefs.edit().putStringSet(KEY_BLOCKED, next).apply()
        _blockedNodes.value = next
    }

    fun unblockNode(nodeIdHex: String) {
        val next = _blockedNodes.value - nodeIdHex
        prefs.edit().putStringSet(KEY_BLOCKED, next).apply()
        _blockedNodes.value = next
    }

    private val _shadowMeshEnabled = MutableStateFlow(prefs.getBoolean(KEY_SHADOWMESH, false))
    /**
     * Opt-in to the ShadowMesh relay network. When false (the default), this
     * device neither relays traffic for other users nor routes its own messages
     * through relays — it only connects directly. See Terms §9 / Privacy §6.
     */
    val shadowMeshEnabled: StateFlow<Boolean> = _shadowMeshEnabled

    private val _peerIpExposurePolicy = MutableStateFlow(
        PeerIpExposurePolicy.fromKey(prefs.getString(KEY_PEER_IP_POLICY, null))
    )
    /**
     * IP Privacy Gate for P2P Discovery (RT-01 Remediation).
     * Controls when direct peer-to-peer IP exposure is permitted vs routing via relay.
     */
    val peerIpExposurePolicy: StateFlow<PeerIpExposurePolicy> = _peerIpExposurePolicy

    fun setPeerIpExposurePolicy(policy: PeerIpExposurePolicy) {
        prefs.edit().putString(KEY_PEER_IP_POLICY, policy.key).apply()
        _peerIpExposurePolicy.value = policy
    }

    private val _duressWipe = MutableStateFlow(prefs.getBoolean(KEY_DURESS_WIPE, false))
    /**
     * When true, the decoy PIN doesn't just show an empty app — it triggers a full
     * cryptographic wipe and closes. For users whose threat model is coercion rather
     * than casual snooping. Off by default (decoy-hide is the safer default).
     */
    val duressWipe: StateFlow<Boolean> = _duressWipe

    /** Crypto-format era of the stored identity/data. 0 = unset (pre-FIPS or first launch);
     *  the FIPS (X-Wing/ML-DSA) build sets it on first run. Drives the one-time upgrade reset. */
    var cryptoEra: Int
        get() = prefs.getInt(KEY_CRYPTO_ERA, 0)
        set(value) { prefs.edit().putInt(KEY_CRYPTO_ERA, value).apply() }

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
    /** Observable so the wake service can start the moment setup completes. */
    val onboardingDoneFlow: StateFlow<Boolean> = _onboardingDone

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()
            _onboardingDone.value = value
        }

    /** Whether we've already prompted once for the Doze battery-optimization exemption. */
    var batteryPromptShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT, false)
        set(value) { prefs.edit().putBoolean(KEY_BATTERY_PROMPT, value).apply() }

    /** Whether we've asked once for the "display over other apps" permission (call bubble). */
    var overlayPromptShown: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_PROMPT, false)
        set(value) { prefs.edit().putBoolean(KEY_OVERLAY_PROMPT, value).apply() }

    /**
     * Whether the brand splash has already been shown on this install. The logo
     * dwell plays only on the very first launch of a freshly initialized app;
     * every later launch hands straight off to the main screen. Cleared by
     * [clearAll] (and by reinstalling), so a fresh start shows it again.
     */
    var splashShown: Boolean
        get() = prefs.getBoolean(KEY_SPLASH_SHOWN, false)
        set(value) { prefs.edit().putBoolean(KEY_SPLASH_SHOWN, value).apply() }

    fun setServerMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVER_MODE, enabled).apply()
        _serverMode.value = enabled
    }

    fun setServerAddress(address: String) {
        val trimmed = address.trim().trimEnd('/')
        prefs.edit().putString(KEY_SERVER_ADDRESS, trimmed).apply()
        _serverAddress.value = trimmed
    }

    /**
     * Point at a custom rendezvous server and remember its certificate pin (trust-on-first-use).
     * [url] must already be validated HTTPS by [com.aura.network.ServerUrlPolicy]; [pin] is the
     * OkHttp `sha256/…` SPKI pin captured on the first connection.
     */
    fun setCustomServer(url: String, pin: String) {
        val trimmed = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_SERVER_ADDRESS, trimmed).putString(KEY_SERVER_PIN, pin).apply()
        _serverAddress.value = trimmed
        _serverPin.value = pin
    }

    /** Return to the bundled default server and drop the custom pin. */
    fun clearCustomServer() {
        prefs.edit().putString(KEY_SERVER_ADDRESS, DEFAULT_SERVER_ADDRESS).remove(KEY_SERVER_PIN).apply()
        _serverAddress.value = DEFAULT_SERVER_ADDRESS
        _serverPin.value = ""
    }

    fun setAdvertisedAddress(address: String) {
        val trimmed = address.trim()
        prefs.edit().putString(KEY_ADVERTISED, trimmed).apply()
        _advertisedAddress.value = trimmed
    }

    fun setDisappearingTimer(timer: DisappearingTimer) {
        prefs.edit().putString(KEY_DISAPPEARING, timer.key).apply()
        _disappearingTimer.value = timer
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.key).apply()
        _themeMode.value = mode
    }

    fun setThemePalette(palette: ThemePalette) {
        prefs.edit().putString(KEY_PALETTE, palette.key).apply()
        _themePalette.value = palette
    }

    fun setDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER, enabled).apply()
        _developerMode.value = enabled
    }

    fun setShadowMeshEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHADOWMESH, enabled).apply()
        _shadowMeshEnabled.value = enabled
    }

    fun setDuressWipe(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DURESS_WIPE, enabled).apply()
        _duressWipe.value = enabled
    }

    fun clearAll() {
        prefs.edit().clear().commit()
        _serverMode.value = false
        _serverAddress.value = DEFAULT_SERVER_ADDRESS
        _serverPin.value = ""
        _advertisedAddress.value = ""
        _disappearingTimer.value = DisappearingTimer.OFF
        _themeMode.value = ThemeMode.SYSTEM
        _themePalette.value = ThemePalette.AURORA
        _developerMode.value = false
        _shadowMeshEnabled.value = false
        _duressWipe.value = false
        _blockedNodes.value = emptySet()
    }

    companion object {
        // Production rendezvous server (DigitalOcean droplet, fronted by Nginx +
        // Let's Encrypt TLS, cert-pinned in RendezvousClient). Reachable from real
        // devices and emulators alike. Override in developer settings for local
        // testing (e.g. http://10.0.2.2:8080 to reach a server on the host).
        const val DEFAULT_SERVER_ADDRESS = "https://api.auroramessenger.com"

        private const val KEY_SERVER_MODE     = "server_mode"
        private const val KEY_ADVERTISED      = "advertised_address"
        private const val KEY_SERVER_ADDRESS  = "server_address"
        private const val KEY_SERVER_PIN      = "server_pin"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_CRYPTO_ERA      = "crypto_era"
        private const val KEY_SPLASH_SHOWN     = "splash_shown"
        private const val KEY_BATTERY_PROMPT   = "battery_prompt_shown"
        private const val KEY_OVERLAY_PROMPT   = "overlay_prompt_shown"
        private const val KEY_DURESS_WIPE      = "duress_wipe"
        private const val KEY_DISAPPEARING    = "disappearing_timer"
        private const val KEY_THEME           = "theme_mode"
        private const val KEY_PALETTE         = "theme_palette"
        private const val KEY_DEVELOPER       = "developer_mode"
        private const val KEY_SHADOWMESH      = "shadowmesh_enabled"
        private const val KEY_BLOCKED         = "blocked_nodes"
        private const val KEY_PEER_IP_POLICY  = "peer_ip_policy"
    }
}

/**
 * Who may learn this device's IP address. Direct paths (TCP, and WebRTC host/server-reflexive
 * ICE candidates) reveal it to the peer; relayed paths (the blind relay, TURN-relayed ICE)
 * reveal it only to our own server. Defaults to [ALWAYS_P2P]: a relay-only mode needs a
 * TURN server for calls, so it is opt-in.
 */
enum class PeerIpExposurePolicy(val key: String, @StringRes val labelRes: Int) {
    STRICT_RELAY_ONLY("strict_relay", R.string.ip_policy_strict_relay),
    VERIFIED_CONTACTS_ONLY("verified_only", R.string.ip_policy_verified_only),
    ALWAYS_P2P("always_p2p", R.string.ip_policy_always_p2p);

    /** True if a direct connection may reveal our IP to a contact in this verification state. */
    fun allowsDirect(mutuallyVerified: Boolean): Boolean = when (this) {
        ALWAYS_P2P -> true
        VERIFIED_CONTACTS_ONLY -> mutuallyVerified
        STRICT_RELAY_ONLY -> false
    }

    companion object {
        fun fromKey(key: String?): PeerIpExposurePolicy =
            entries.firstOrNull { it.key == key } ?: ALWAYS_P2P
    }
}

/** Appearance preference. SYSTEM follows the OS light/dark setting. */
enum class ThemeMode(val key: String, @StringRes val labelRes: Int) {
    SYSTEM("system", R.string.theme_system),
    LIGHT ("light",  R.string.theme_light),
    DARK  ("dark",   R.string.theme_dark);

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Colour palette. AURORA is the current neon-aurora look; CHERISH restores the
 * original deep-plum / soft-rose theme (kept as a nostalgic alternative).
 */
enum class ThemePalette(val key: String, @StringRes val labelRes: Int) {
    AURORA ("aurora",  R.string.palette_aurora),
    CHERISH("cherish", R.string.palette_cherish);

    companion object {
        fun fromKey(key: String?): ThemePalette = entries.firstOrNull { it.key == key } ?: AURORA
    }
}

/** Per-app default disappearing-message timer (Phase 6 wires the actual deletion). */
enum class DisappearingTimer(val key: String, @StringRes val labelRes: Int) {
    OFF      ("off", R.string.timer_off),
    ONE_HOUR ("1h",  R.string.timer_1h),
    ONE_DAY  ("24h", R.string.timer_24h),
    ONE_WEEK ("7d",  R.string.timer_7d);

    companion object {
        fun fromKey(key: String?): DisappearingTimer =
            entries.firstOrNull { it.key == key } ?: OFF
    }
}
