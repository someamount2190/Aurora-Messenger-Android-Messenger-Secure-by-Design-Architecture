package com.aura.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.crypto.toHex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 8: app lock with a real PIN and a decoy PIN.
 *
 * - The real PIN unlocks the normal app.
 * - The decoy PIN unlocks a "clean" view: the app behaves as if it has no
 *   contacts or messages. (Enforced by [decoyActive] — the data layer filters
 *   to empty while decoy mode is on.)
 * - PINs are stored only as a salted, slow PBKDF2-HMAC-SHA256 hash (per-install
 *   random salt) in EncryptedSharedPreferences — so a short PIN is expensive to
 *   brute-force even if the encrypted store were ever extracted.
 * - Repeated wrong PINs trigger an exponential lockout, throttling on-device
 *   guessing.
 */
@Singleton
class AppLock internal constructor(
    private val prefs: SharedPreferences,
    /** Wall clock, injectable so the exponential-lockout logic is testable deterministically. */
    private val now: () -> Long
) {
    /** Production entry point: PIN hashes live in EncryptedSharedPreferences (Keystore-backed). */
    @Inject constructor(@ApplicationContext context: Context) : this(encryptedPrefs(context), { System.currentTimeMillis() })

    enum class UnlockResult { REAL, DECOY, WRONG, LOCKED_OUT }

    /** Outcome of a PIN-gated lock change (set / change / disable). */
    enum class LockChangeResult { OK, WRONG_PIN, LOCKED_OUT }

    private val _locked = MutableStateFlow(isLockEnabled())
    val locked: StateFlow<Boolean> = _locked

    private val _enabled = MutableStateFlow(isLockEnabled())
    /** True while a real PIN exists. Unlike [locked], this stays true while unlocked. */
    val enabled: StateFlow<Boolean> = _enabled

    private val _decoyActive = MutableStateFlow(false)
    /** True when the app was unlocked with the decoy PIN — data layer shows nothing. */
    val decoyActive: StateFlow<Boolean> = _decoyActive

    private val _lockoutUntil = MutableStateFlow(prefs.getLong(KEY_LOCKED_UNTIL, 0L))
    /** Wall-clock ms until which unlock attempts are refused (0 = not locked out). */
    val lockoutUntil: StateFlow<Long> = _lockoutUntil

    fun isLockEnabled(): Boolean = prefs.contains(KEY_REAL_HASH)

    /**
     * Set the first PIN, or change it. Changing an existing lock requires [currentPin]:
     * knowing only the new PIN must not let someone take over or replace the lock.
     */
    fun setPin(newPin: String, currentPin: String? = null): LockChangeResult {
        if (isLockEnabled()) {
            if (currentPin == null) return LockChangeResult.WRONG_PIN
            when (tryUnlock(currentPin)) {
                UnlockResult.REAL -> Unit
                UnlockResult.LOCKED_OUT -> return LockChangeResult.LOCKED_OUT
                else -> return LockChangeResult.WRONG_PIN
            }
        }
        prefs.edit()
            .putString(KEY_SALT, ensureSalt())
            .putString(KEY_REAL_HASH, hash(newPin))
            .apply()
        resetAttempts()
        _locked.value = true
        _enabled.value = true
        _decoyActive.value = false
        return LockChangeResult.OK
    }

    /** Set or replace the decoy PIN. Replacing an existing one requires [currentPin]. */
    fun setDecoyPin(newPin: String, currentPin: String? = null): LockChangeResult {
        if (isLockEnabled()) {
            if (currentPin == null) return LockChangeResult.WRONG_PIN
            when (tryUnlock(currentPin)) {
                UnlockResult.REAL -> Unit
                UnlockResult.LOCKED_OUT -> return LockChangeResult.LOCKED_OUT
                else -> return LockChangeResult.WRONG_PIN
            }
        }
        prefs.edit().putString(KEY_DECOY_HASH, hash(newPin)).apply()
        return LockChangeResult.OK
    }

    /**
     * Turn the app lock off. Requires the **real** current PIN: someone holding an
     * unlocked phone must not be able to simply remove the lock. The decoy PIN is
     * rejected here (it only opens the empty view and must not tear down the real lock).
     */
    fun disableLock(currentPin: String): LockChangeResult {
        if (!isLockEnabled()) return LockChangeResult.OK
        return when (tryUnlock(currentPin)) {
            UnlockResult.REAL -> {
                forceDisableLock()
                LockChangeResult.OK
            }
            UnlockResult.LOCKED_OUT -> LockChangeResult.LOCKED_OUT
            else -> LockChangeResult.WRONG_PIN
        }
    }

    /**
     * Unguarded removal, for [SecureWipe] only — by the time it runs the DB key and
     * identity vault are already destroyed, and there is no user to ask for a PIN.
     */
    internal fun forceDisableLock() {
        prefs.edit().remove(KEY_REAL_HASH).remove(KEY_DECOY_HASH).apply()
        resetAttempts()
        _locked.value = false
        _enabled.value = false
        _decoyActive.value = false
    }

    fun lock() { if (isLockEnabled()) _locked.value = true }

    fun tryUnlock(pin: String): UnlockResult {
        val nowMs = now()
        val until = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        if (nowMs < until) {
            _lockoutUntil.value = until
            return UnlockResult.LOCKED_OUT
        }
        val candidate = hash(pin)
        return when {
            prefs.getString(KEY_REAL_HASH, null) == candidate -> {
                resetAttempts()
                _decoyActive.value = false
                _locked.value = false
                UnlockResult.REAL
            }
            prefs.getString(KEY_DECOY_HASH, null) == candidate -> {
                resetAttempts()
                _decoyActive.value = true
                _locked.value = false
                UnlockResult.DECOY
            }
            else -> {
                registerFailure(nowMs)
                UnlockResult.WRONG
            }
        }
    }

    /** Count a wrong attempt; after [LOCKOUT_THRESHOLD] start an exponential lockout. */
    private fun registerFailure(now: Long) {
        val fails = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAIL_COUNT, fails)
        if (fails >= LOCKOUT_THRESHOLD) {
            val steps = (fails - LOCKOUT_THRESHOLD).coerceAtMost(5)
            val delay = (LOCKOUT_BASE_MS shl steps).coerceAtMost(LOCKOUT_MAX_MS)
            val until = now + delay
            editor.putLong(KEY_LOCKED_UNTIL, until)
            _lockoutUntil.value = until
        }
        editor.apply()
    }

    private fun resetAttempts() {
        prefs.edit().remove(KEY_FAIL_COUNT).remove(KEY_LOCKED_UNTIL).apply()
        _lockoutUntil.value = 0L
    }

    private fun ensureSalt(): String =
        prefs.getString(KEY_SALT, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_SALT, it).apply()
        }

    // Slow, salted PIN hash. PBKDF2-HMAC-SHA256 is built into Android (no extra
    // dependency) and makes brute-forcing a short PIN from a leaked hash expensive —
    // unlike the old single-pass HKDF. Argon2id would be stronger but needs a lib.
    private fun hash(pin: String): String {
        val salt = ensureSalt().toByteArray()
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        /** The Keystore-backed encrypted store used in production. */
        fun encryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context, "aura_lock", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        const val KEY_SALT = "salt"
        const val KEY_REAL_HASH = "real_hash"
        const val KEY_DECOY_HASH = "decoy_hash"
        const val KEY_FAIL_COUNT = "fail_count"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val LOCKOUT_THRESHOLD = 5
        const val LOCKOUT_BASE_MS = 30_000L
        const val LOCKOUT_MAX_MS = 15 * 60 * 1000L
        const val PBKDF2_ITERATIONS = 200_000
    }
}
