package com.aura.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared links are only read when they point at another app's content, and only up to a cap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IncomingShareUrisTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** Another app's provider that reports a fixed content type. */
    class TypedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = uri.getQueryParameter("type")
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test fun anotherAppsContentLinkIsAccepted() {
        assertTrue(IncomingShareUris.isAcceptable(ctx, Uri.parse("content://com.android.providers.media.documents/document/image%3A12")))
    }

    @Test fun fileLinksAreRejected() {
        val own = "file://${ctx.filesDir.parentFile!!.absolutePath}/databases/aura.db"
        assertFalse(IncomingShareUris.isAcceptable(ctx, Uri.parse(own)), "file path into Aurora's data dir")
        assertFalse(IncomingShareUris.isAcceptable(ctx, Uri.parse("file:///sdcard/Download/x.jpg")))
    }

    @Test fun aurorasOwnProvidersAreRejected() {
        val pkg = ctx.packageName
        assertFalse(IncomingShareUris.isAcceptable(ctx, Uri.parse("content://$pkg.decryptedmedia/abcd")))
        assertFalse(IncomingShareUris.isAcceptable(ctx, Uri.parse("content://$pkg.fileprovider/shared/a.jpg")))
        assertFalse(IncomingShareUris.isAcceptable(ctx, Uri.parse("content://$pkg/x")))
    }

    @Test fun otherSchemesAreRejected() {
        assertFalse(IncomingShareUris.isAcceptable(ctx, Uri.parse("android.resource://${ctx.packageName}/raw/x")))
        assertFalse(IncomingShareUris.isAcceptable(ctx, Uri.parse("https://example.com/x.jpg")))
    }

    @Test fun boundedReadReturnsContentWithinTheCap() {
        val uri = Uri.parse("content://other.app/a")
        val bytes = ByteArray(200_000) { it.toByte() }
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        assertContentEquals(bytes, IncomingShareUris.readBounded(ctx.contentResolver, uri, 200_000))
    }

    @Test fun boundedReadStopsAtTheCap() {
        val uri = Uri.parse("content://other.app/huge")
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(200_001)))
        assertNull(IncomingShareUris.readBounded(ctx.contentResolver, uri, 200_000))
    }

    @Test fun mediaKindComesFromTheProviderNotTheSender() {
        Robolectric.setupContentProvider(TypedProvider::class.java, "other.typed")
        fun kind(type: String) = IncomingShareUris.mediaKind(ctx, Uri.parse("content://other.typed/x?type=$type"), "image")
        assertEquals("video", kind("video/mp4"), "a video labelled image is sent as video")
        assertEquals("image", kind("image/png"))
        assertNull(kind("application/octet-stream"), "non-media content is refused")
    }
}
