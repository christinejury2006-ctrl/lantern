package com.lantern.library.studio

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

internal object WebFetch {
    private const val MAX_HTML = 2_000_000
    /** Skip images larger than 8 MB so a chapter cannot exhaust RAM. No downscale. */
    private const val MAX_IMAGE = 8_000_000
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Lore/3.5.0 (Studio)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class Page(val url: String, val html: String)

    fun page(rawUrl: String): Page {
        val url = normalizeHttps(rawUrl) ?: error("Use an https:// link.")
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "text/html").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Could not open that page (${res.code}).")
            val type = res.body?.contentType()?.toString().orEmpty().lowercase()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("text/plain") && !type.contains("xml")) {
                error("That link is not a webpage.")
            }
            val bytes = res.body?.bytes() ?: error("Empty page.")
            if (bytes.size > MAX_HTML) error("That page is too large to analyze.")
            val html = bytes.toString(Charsets.UTF_8)
            val finalUrl = res.request.url.toString()
            return Page(finalUrl, html)
        }
    }

    fun image(rawUrl: String, dest: File): Boolean {
        val url = normalizeHttps(rawUrl) ?: return false
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use false
                val bytes = res.body?.bytes() ?: return@use false
                if (bytes.size < 80 || bytes.size > MAX_IMAGE) return@use false
                dest.writeBytes(bytes)
                dest.exists() && dest.length() > 0L
            }
        }.getOrDefault(false)
    }

    fun webDir(cacheDir: File): File = File(cacheDir, "studio/web").apply { mkdirs() }

    fun clearWebDir(cacheDir: File) {
        webDir(cacheDir).listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    fun normalizeHttps(raw: String): String? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        if (!t.contains("://")) t = "https://$t"
        if (t.startsWith("http://")) t = "https://" + t.removePrefix("http://")
        if (!t.startsWith("https://")) return null
        return t
    }
}
