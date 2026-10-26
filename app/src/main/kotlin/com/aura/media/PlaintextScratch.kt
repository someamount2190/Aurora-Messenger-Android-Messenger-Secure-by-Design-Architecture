package com.aura.media

import android.content.Context
import java.io.File

/**
 * The one place Aurora writes decrypted media to disk, and its cleanup.
 *
 * Media is otherwise decrypted only in memory (playback, thumbnails, sharing). The exception is
 * a voice message while it's being recorded: MediaRecorder needs a seekable file for MP4, so the
 * recording lives in [recordingDir] until it's sealed (or cancelled) and then deleted. [sweep]
 * removes anything a crash left behind, plus plaintext temp files older builds wrote.
 */
object PlaintextScratch {

    fun recordingDir(context: Context): File = File(context.cacheDir, "voice")

    /** Delete leftover plaintext. Runs at launch, when no recording can be in progress. */
    fun sweep(context: Context) {
        recordingDir(context).deleteRecursively()
        // Written by older builds: share-sheet copies and voice-playback copies.
        File(context.cacheDir, "shared").deleteRecursively()
        context.cacheDir.listFiles { f -> f.name.startsWith("play-") && f.name.endsWith(".m4a") }
            ?.forEach { it.delete() }
    }

    /** Delete the whole cache directory's contents (secure wipe). */
    fun wipe(context: Context) {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
