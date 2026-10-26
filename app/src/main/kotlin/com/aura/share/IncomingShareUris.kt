package com.aura.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

/**
 * Rules for reading media another app hands us (system share sheet or the gallery picker).
 *
 * Aurora reads a shared link with its own permissions, so a malicious app could share a link to
 * Aurora's *own* files (a `file://` path into its data directory, or one of its providers) and
 * have the user send them to a contact. Only `content://` links served by another app are read,
 * and reads stop at a size cap instead of loading an arbitrarily large file into memory.
 */
object IncomingShareUris {

    /** True if [uri] may be read on behalf of a share: a content link served by another app. */
    fun isAcceptable(context: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        val pkg = context.packageName
        if (authority == pkg || authority.startsWith("$pkg.")) return false
        val owner = runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
        return owner != pkg
    }

    /** "image" or "video" from what the provider says the content is, or null if it's neither. */
    fun mediaKind(context: Context, uri: Uri, fallback: String): String? {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: return fallback
        return when {
            type.startsWith("image/") -> "image"
            type.startsWith("video/") -> "video"
            else -> null
        }
    }

    /** Read at most [maxBytes]; null if the content is larger, unreadable, or missing. */
    fun readBounded(resolver: ContentResolver, uri: Uri, maxBytes: Int): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > maxBytes) return@use null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()
}
