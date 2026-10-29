package com.lantern.library.data

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The shape of a Drive upload, pinned.
 *
 * A `multipart/related` upload to Drive is two parts: the metadata JSON, then the file or the JSON
 * snapshot. The original code passed `Headers.headersOf("Content-Type", …)` to `addPart`, which
 * OkHttp 4 rejects outright:
 *
 * ```
 * java.lang.IllegalArgumentException: Unexpected header: Content-Type
 *     at okhttp3.MultipartBody$Part$Companion.create(MultipartBody.kt:201)
 * ```
 *
 * That exception is thrown while *building* the request, before any network call, and it escaped
 * into `viewModelScope` — so every account sync crashed the app, and the sync never got as far as
 * writing anything. The first test is the crash itself, kept so the rule cannot be forgotten; the
 * rest pin the replacement.
 */
class DriveMultipartTest {

    private val related = "multipart/related".toMediaType()

    /** The bug, reproduced: a part may not carry a Content-Type header. */
    @Test
    fun `okhttp rejects a Content-Type header on a part`() {
        val body = "{}".toRequestBody("application/json; charset=UTF-8".toMediaType())
        val thrown = runCatching {
            MultipartBody.Part.create(Headers.headersOf("Content-Type", "application/json"), body)
        }.exceptionOrNull()
        assertNotNull("OkHttp should refuse this, and it is the crash we are guarding", thrown)
        assertEquals(IllegalArgumentException::class.java, thrown!!.javaClass)
    }

    /** What the app does instead: the media type lives on the body, and the part is accepted. */
    @Test
    fun `a part built from a body carries the body's media type`() {
        val body = DriveLibrary.jsonBody("""{"name":"shelf.json"}""")
        assertEquals("application/json", body.contentType()?.type + "/" + body.contentType()?.subtype)
        val multipart = MultipartBody.Builder()
            .setType(related)
            .addPart(body)
            .build()
        assertEquals(1, multipart.parts.size)
        // No header list was supplied, so there is nothing for OkHttp to refuse; it writes the
        // body's media type into the part itself when the request is encoded.
        assertEquals(0, multipart.parts[0].headers?.size ?: 0)
        assertEquals("json", multipart.parts[0].body.contentType()?.subtype)
    }

    /** The real upload: metadata then payload, both typed, in that order. */
    @Test
    fun `an upload body is metadata then payload`() {
        val multipart = MultipartBody.Builder()
            .setType(related)
            .addPart(DriveLibrary.jsonBody("""{"name":"book.epub"}"""))
            .addPart(DriveLibrary.mediaBody("not really an epub".toByteArray(), "application/epub+zip"))
            .build()
        assertEquals(2, multipart.parts.size)
        assertEquals("json", multipart.parts[0].body.contentType()?.subtype)
        assertEquals("epub+zip", multipart.parts[1].body.contentType()?.subtype)
        // The boundary must be present, or Drive reads the whole thing as one opaque blob.
        assertEquals(related.type, multipart.contentType()?.type)
        assertEquals(related.subtype, multipart.contentType()?.subtype)
        assertNotNull(multipart.boundary)
    }
}
