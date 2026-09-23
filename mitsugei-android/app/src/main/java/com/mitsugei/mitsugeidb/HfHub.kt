package com.mitsugei.mitsugeidb

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class HfFile(val path: String, val size: Long)

class HfHubException(msg: String) : Exception(msg)

/**
 * Talks to Hugging Face directly: the Hub's tree API to resolve a glob
 * prefix into concrete Parquet shard paths, and CDN byte-range GETs to
 * pull only the footer + specific column chunks out of each shard.
 * No DuckDB, no dataset-viewer, no server of ours in this path at all.
 */
object HfHub {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authHeader(token: String?): Pair<String, String>? =
        token?.let { "Authorization" to "Bearer $it" }

    /**
     * Lists every file under [subpathPrefix] in [repo]@[revision] ending in
     * ".parquet", walking the Hub's paginated tree API (Link: rel="next").
     * `repo` is "org/name"; `revision` is typically "refs/convert/parquet"
     * for the auto-generated Parquet export used by every preset in
     * retrieval/lakes.py.
     */
    fun listShards(repo: String, revision: String, subpathPrefix: String, token: String?): List<HfFile> {
        val out = ArrayList<HfFile>()
        var url = "https://huggingface.co/api/datasets/$repo/tree/$revision/$subpathPrefix?recursive=true&limit=1000"
        var guard = 0
        while (url.isNotEmpty() && guard < 50) {
            guard++
            val reqBuilder = Request.Builder().url(url)
            authHeader(token)?.let { reqBuilder.addHeader(it.first, it.second) }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HfHubException("tree listing failed: HTTP ${resp.code} for $url")
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
        // Format: <https://...>; rel="next", <https://...>; rel="last"
        for (part in linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                val start = part.indexOf('<')
                val end = part.indexOf('>')
                if (start >= 0 && end > start) return part.substring(start + 1, end)
            }
        }
        return null
    }

    fun resolveUrl(repo: String, revision: String, path: String): String =
        "https://huggingface.co/datasets/$repo/resolve/$revision/$path"

    /** Inclusive byte range [start, endInclusive]. */
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

    /**
     * Total file size, used when the tree listing didn't report one.
     * Asks for byte 0 only and reads the total off the response's
     * `Content-Range: bytes 0-0/<total>` header — one tiny request,
     * no full-file GET.
     */
    fun remoteSize(url: String, token: String?): Long {
        val reqBuilder = Request.Builder().url(url).addHeader("Range", "bytes=0-0")
        authHeader(token)?.let { reqBuilder.addHeader(it.first, it.second) }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HfHubException("size probe failed: HTTP ${resp.code} for $url")
            val contentRange = resp.header("Content-Range")
                ?: throw HfHubException("server didn't return Content-Range for $url — can't determine size")
            val total = contentRange.substringAfter('/', "").trim()
            return total.toLongOrNull()
                ?: throw HfHubException("unparseable Content-Range \"$contentRange\" for $url")
        }
    }
}
