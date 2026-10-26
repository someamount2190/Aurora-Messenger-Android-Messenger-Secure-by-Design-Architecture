package com.aura.media

import android.content.Context
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared media is served from memory, through unguessable links that expire and can be wiped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DecryptedMediaProviderTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var clock = 1_000_000L
    private lateinit var provider: DecryptedMediaProvider

    @Before fun setUp() {
        provider = Robolectric.setupContentProvider(DecryptedMediaProvider::class.java, DecryptedMediaProvider.authority(ctx))
        DecryptedMediaProvider.now = { clock }
        DecryptedMediaProvider.clearAll()
    }

    @After fun tearDown() {
        DecryptedMediaProvider.clearAll()
        DecryptedMediaProvider.now = { System.currentTimeMillis() }
    }

    @Test fun servesTheBytesWithNameSizeAndType() {
        val bytes = ByteArray(70_000) { it.toByte() }
        val uri = DecryptedMediaProvider.publish(ctx, bytes, "image/jpeg", "aurora-m1.jpg")

        assertEquals("image/jpeg", ctx.contentResolver.getType(uri))
        ctx.contentResolver.query(uri, null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("aurora-m1.jpg", c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(70_000L, c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val read = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertContentEquals(bytes, read)
    }

    @Test fun linksAreUnguessableAndUnique() {
        val a = DecryptedMediaProvider.publish(ctx, byteArrayOf(1), "image/jpeg", "a.jpg")
        val b = DecryptedMediaProvider.publish(ctx, byteArrayOf(1), "image/jpeg", "b.jpg")
        assertNotEquals(a, b)
        assertEquals(64, a.lastPathSegment!!.length, "256-bit random token")
    }

    @Test fun unknownLinkIsRefused() {
        val uri = DecryptedMediaProvider.publish(ctx, byteArrayOf(1), "image/jpeg", "a.jpg")
        val forged = uri.buildUpon().path("/" + "0".repeat(64)).build()
        assertFailsWith<FileNotFoundException> { ctx.contentResolver.openInputStream(forged) }
        assertNull(ctx.contentResolver.query(forged, null, null, null, null))
    }

    @Test fun linksExpireAndTheirBytesAreZeroed() {
        val bytes = ByteArray(16) { 7 }
        val uri = DecryptedMediaProvider.publish(ctx, bytes, "image/jpeg", "a.jpg")
        clock += DecryptedMediaProvider.TTL_MS
        assertFailsWith<FileNotFoundException> { ctx.contentResolver.openInputStream(uri) }
        assertTrue(bytes.all { it == 0.toByte() }, "expired plaintext is zeroed")
    }

    @Test fun holdsAtMostTwoSharesAndZeroesTheEvictedOne() {
        val first = ByteArray(8) { 9 }
        val u1 = DecryptedMediaProvider.publish(ctx, first, "image/jpeg", "1.jpg")
        clock += 1
        DecryptedMediaProvider.publish(ctx, byteArrayOf(2), "image/jpeg", "2.jpg")
        clock += 1
        DecryptedMediaProvider.publish(ctx, byteArrayOf(3), "image/jpeg", "3.jpg")
        assertFailsWith<FileNotFoundException> { ctx.contentResolver.openInputStream(u1) }
        assertTrue(first.all { it == 0.toByte() })
    }

    @Test fun clearAllZeroesAndRevokesEverything() {
        val bytes = ByteArray(8) { 5 }
        val uri = DecryptedMediaProvider.publish(ctx, bytes, "video/mp4", "a.mp4")
        DecryptedMediaProvider.clearAll()
        assertFailsWith<FileNotFoundException> { ctx.contentResolver.openInputStream(uri) }
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun isReadOnly() {
        val uri = DecryptedMediaProvider.publish(ctx, byteArrayOf(1), "image/jpeg", "a.jpg")
        assertFailsWith<FileNotFoundException> { provider.openFile(uri, "w") }
        assertFailsWith<FileNotFoundException> { provider.openFile(uri, "rw") }
    }

    @Test fun manifestKeepsTheProviderPrivateAndHasNoFileProvider() {
        // Read the real manifest (Robolectric here doesn't load the merged one). Unit tests run
        // with the module directory as working directory.
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(java.io.File("src/main/AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val providers = doc.getElementsByTagName("provider")
        val names = (0 until providers.length).map { (providers.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") }
        assertFalse(names.any { it.contains("FileProvider") }, "no file-backed provider for decrypted media")
        val p = (0 until providers.length).map { providers.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ns, "name") == ".media.DecryptedMediaProvider" }
        assertEquals("false", p.getAttributeNS(ns, "exported"), "only apps granted a specific link may read it")
        assertEquals("true", p.getAttributeNS(ns, "grantUriPermissions"))
    }
}
