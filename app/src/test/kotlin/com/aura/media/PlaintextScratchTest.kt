package com.aura.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaintextScratchTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun file(dir: File, name: String) = File(dir, name).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test fun sweepRemovesLeftoverPlaintextOnly() {
        val recording = file(PlaintextScratch.recordingDir(ctx), "rec-1.m4a")
        val oldShare = file(File(ctx.cacheDir, "shared"), "aurora-m1.jpg")
        val oldPlayback = file(ctx.cacheDir, "play-m2.m4a")
        val unrelated = file(ctx.cacheDir, "other.bin")
        val encryptedMedia = file(File(ctx.filesDir, "media"), "m3.enc")

        PlaintextScratch.sweep(ctx)

        assertFalse(recording.exists(), "interrupted recording removed")
        assertFalse(oldShare.exists(), "old share copy removed")
        assertFalse(oldPlayback.exists(), "old playback copy removed")
        assertTrue(unrelated.exists(), "other cache entries untouched")
        assertTrue(encryptedMedia.exists(), "encrypted media untouched")
    }

    @Test fun wipeEmptiesTheCacheButNotEncryptedStorage() {
        file(File(ctx.cacheDir, "shared"), "a.jpg")
        file(ctx.cacheDir, "b.bin")
        val encryptedMedia = file(File(ctx.filesDir, "media"), "m3.enc")

        PlaintextScratch.wipe(ctx)

        assertTrue(ctx.cacheDir.listFiles().isNullOrEmpty())
        assertTrue(encryptedMedia.exists(), "encrypted media is SecureWipe's job via EncryptedMediaStore")
    }
}
