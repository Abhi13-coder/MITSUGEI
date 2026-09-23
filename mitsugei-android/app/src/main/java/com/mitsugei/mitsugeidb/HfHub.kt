package com.mitsugei.mitsugeidb

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class HfFile(val path: String, val size: Long)

class HfHubException(msg: String) : Exception(msg)

/**
 * Hugging Face Hub client: list parquet shards + byte-range downloads.
 *
 * IMPORTANT: OkHttp decodes "%2F" in string URLs back to "/". Building the
 * tree/resolve URL with [HttpUrl.Builder.addPathSegment] keeps revision
 * values like "refs/convert/parquet" as a *single* encoded segment
 * (refs%2Fconvert%2Fparquet), which the Hub requires.
 */
object HfHub {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun authHeader(token: String?): Pair<String, String>? =
        token?.let { "Authorization" to "Bearer $it" }

    private fun treeUrl(repo: String, revision: String, subpathPrefix: String): String {
        val b = "https://huggingface.co".toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("datasets")
        for (part in repo.split("/").filter { it.isNotEmpty() }) {
            b.addPathSegment(part)
        }
        b.addPathSegment("tree")
        b.addPathSegment(revision.trim('/'))
        for (part in subpathPrefix.split("/").filter { it.isNotEmpty() }) {
            b.addPathSegment(part)
        }
        b.addQueryParameter("recursive", "true")
        b.addQueryParameter("limit", "1000")
        return b.build().toString()
    }

    private fun resolveUrlBuilt(repo: String, revision: String, path: String): String {
        val b = "https://huggingface.co".toHttpUrl().newBuilder()
            .addPathSegment("datasets")
        for (part in repo.split("/").filter { it.isNotEmpty() }) {
            b.addPathSegment(part)
        }
        b.addPathSegment("resolve")
        b.addPathSegment(revision.trim('/'))
        for (part in path.split("/").filter { it.isNotEmpty() }) {
            b.addPathSegment(part)
        }
        return b.build().toString()
    }

    fun listShards(repo: String, revision: String, subpathPrefix: String, token: String?): List<HfFile> {
        val out = ArrayList<HfFile>()
        var url = treeUrl(repo, revision, subpathPrefix)
        var guard = 0
        while (url.isNotEmpty() && guard < 50) {
            guard++
            val reqBuilder = Request.Builder().url(url)
            authHeader(token)?.let { reqBuilder.addHeader(it.first, it.second) }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw HfHubException("tree listing failed: HTTP ${resp.code} for $url")
                }
                val body = resp.body?.string() ?: "[]"
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("type") == "file") {
                        val path = obj.getString("path")
                        if (path.endsWith(".parquet")) {
                            out.add(HfFile(path, obj.optLong("size", -1L)))
                        }
                    }
                }
                val link = resp.header("Link")
                url = parseNextLink(link) ?: ""
            }
        }
        return out.sortedBy { it.path }
    }

    private fun parseNextLink(linkHeader: String?): String? {
        if (linkHeader == null) return null
        for (part in linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                val start = part.indexOf('<')
                val end = part.indexOf('>')
                if (start >= 0 && end > start) {
                    return part.substring(start + 1, end)
                }
            }
        }
        return null
    }

    fun resolveUrl(repo: String, revision: String, path: String): String =
        resolveUrlBuilt(repo, revision, path)

    fun rangeGet(url: String, start: Long, endInclusive: Long, token: String?): ByteArray {
        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("Range", "bytes=$start-$endInclusive")
        authHeader(token)?.let { reqBuilder.addHeader(it.first, it.second) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code != 206 && resp.code != 200) {
                throw HfHubException("range fetch failed: HTTP ${resp.code} for $url [$start-$endInclusive]")
            }
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    fun remoteSize(url: String, token: String?): Long {
        val reqBuilder = Request.Builder().url(url).addHeader("Range", "bytes=0-0")
        authHeader(token)?.let { reqBuilder.addHeader(it.first, it.second) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw HfHubException("size probe failed: HTTP ${resp.code} for $url")
            }
            val contentRange = resp.header("Content-Range")
                ?: throw HfHubException("server didn't return Content-Range for $url")
            val total = contentRange.substringAfter('/', "").trim()
            return total.toLongOrNull()
                ?: throw HfHubException("unparseable Content-Range \"$contentRange\" for $url")
        }
    }
}
