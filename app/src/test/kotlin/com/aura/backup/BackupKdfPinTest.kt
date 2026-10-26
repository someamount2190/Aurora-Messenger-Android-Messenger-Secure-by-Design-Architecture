package com.aura.backup

import com.aura.crypto.toHex
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Backups are only restorable if Argon2id derives the same key forever. These pins catch a
 * library upgrade (or a parameter edit) that would silently make every existing backup
 * unrestorable: one independent RFC vector, and the app's exact backup parameters pinned at the
 * output Bouncy Castle 1.84 produced when the first backups were made.
 */
class BackupKdfPinTest {

    @Test fun argon2id_matchesRfc9106Vector() {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3).withMemoryAsKB(32).withParallelism(4)
            .withSalt(ByteArray(16) { 2 }).withSecret(ByteArray(8) { 3 }).withAdditional(ByteArray(12) { 4 })
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(ByteArray(32) { 1 }, out)
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", out.toHex())
    }

    @Test fun backupKeyDerivation_isUnchanged() {
        val key = Backups.deriveKey("correct horse battery staple".toCharArray(), ByteArray(Backups.SALT_BYTES) { 0x5A })
        assertEquals("64cff1e4ea6624a89d14ef5654ac56a6831dc6ad4a186b1834db4962b5bd51e5", key.toHex())
    }
}
