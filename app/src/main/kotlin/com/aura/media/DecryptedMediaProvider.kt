package com.aura.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands decrypted media to another app (the system share sheet) without writing plaintext to
 * disk. [publish] keeps the bytes in memory under a random, unguessable link; the receiving app
 * reads them through a pipe, with read access granted per link by the share intent. The provider
 * is not exported, so no other app can read a link it wasn't granted.
 *
 * A link stays readable for [TTL_MS] (the receiving app may read lazily, e.g. when an email is
 * sent), at most [MAX_ENTRIES] are held at once, and [clearAll] zeroes and drops them all (wipe).
 * Entries never outlive the process. A pipe isn't seekable, so the rare app that needs to seek
 * within a shared file can't read it; apps that copy the stream (messengers, mail) work.
 */
class DecryptedMediaProvider : ContentProvider() {

    private class Entry(val bytes: ByteArray, val mimeType: String, val displayName: String, val expiresAt: Long)

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_ENTRIES = 2

        private val entries = ConcurrentHashMap<String, Entry>()
        private val rng = SecureRandom()
        /** Wall clock, replaceable in tests. */
        internal var now: () -> Long = { System.currentTimeMillis() }

        fun authority(context: Context) = "${context.packageName}.decryptedmedia"

        /** Hold [bytes] in memory and return a content link to them (grant read access when sharing). */
        @Synchronized
        fun publish(context: Context, bytes: ByteArray, mimeType: String, displayName: String): Uri {
            evictExpired()
            while (entries.size >= MAX_ENTRIES) {
                val oldest = entries.entries.minByOrNull { it.value.expiresAt } ?: break
                entries.remove(oldest.key)?.bytes?.fill(0)
            }
            val token = ByteArray(32).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            entries[token] = Entry(bytes, mimeType, displayName, now() + TTL_MS)
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(token).build()
        }

        /** Zero and drop every pending share (secure wipe). */
        @Synchronized
        fun clearAll() {
            entries.values.forEach { it.bytes.fill(0) }
            entries.clear()
        }

        @Synchronized
        private fun evictExpired() {
            val t = now()
            entries.entries.removeIf { (_, e) -> (e.expiresAt <= t).also { if (it) e.bytes.fill(0) } }
        }

        private fun lookup(uri: Uri): Entry? {
            evictExpired()
            return uri.lastPathSegment?.let { entries[it] }
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = lookup(uri)?.mimeType

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val e = lookup(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> e.displayName
                    OpenableColumns.SIZE -> e.bytes.size.toLong()
                    else -> null
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only")
        val e = lookup(uri) ?: throw FileNotFoundException("expired or unknown")
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                runCatching { out.write(e.bytes) }
            }
        }.apply { name = "aurora-share-pipe"; isDaemon = true }.start()
        return read
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
