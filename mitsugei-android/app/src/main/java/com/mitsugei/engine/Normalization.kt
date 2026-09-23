package com.mitsugei.engine

import java.security.MessageDigest
import java.text.Normalizer

/** Port of mitsugei/core/normalization.py. Uses manual URL splitting rather
 * than java.net.URI because URI is much stricter about malformed input than
 * Python's urlsplit — and lake/crawl data is full of mildly malformed URLs
 * that shouldn't crash ingestion, only get normalized best-effort. */
object Normalization {

    private val DEFAULT_PORTS = mapOf("http" to "80", "https" to "443")
    private val WS_RE = Regex("\\s+")
    private val TOKEN_RE = Regex("[a-z0-9]+")

    private data class SplitUrl(val scheme: String, val netloc: String, val path: String, val query: String, val fragment: String)

    private fun split(rawUrl: String): SplitUrl {
        var rest = rawUrl.trim()
        var fragment = ""
        val hashIdx = rest.indexOf('#')
        if (hashIdx >= 0) { fragment = rest.substring(hashIdx + 1); rest = rest.substring(0, hashIdx) }

        var scheme = ""
        val schemeIdx = rest.indexOf("://")
        if (schemeIdx > 0 && rest.substring(0, schemeIdx).matches(Regex("[A-Za-z][A-Za-z0-9+.-]*"))) {
            scheme = rest.substring(0, schemeIdx)
            rest = rest.substring(schemeIdx + 3)
        }

        var query = ""
        val qIdx = rest.indexOf('?')
        if (qIdx >= 0) { query = rest.substring(qIdx + 1); rest = rest.substring(0, qIdx) }

        var netloc = ""
        var path = rest
        if (scheme.isNotEmpty() || rest.startsWith("//")) {
            val body = if (rest.startsWith("//")) rest.substring(2) else rest
            val slashIdx = body.indexOf('/')
            if (slashIdx >= 0) { netloc = body.substring(0, slashIdx); path = body.substring(slashIdx) } else { netloc = body; path = "" }
        }
        return SplitUrl(scheme, netloc, path, query, fragment)
    }

    private fun parseQueryPairs(query: String): List<Pair<String, String>> {
        if (query.isEmpty()) return emptyList()
        return query.split("&").filter { it.isNotEmpty() }.map { pair ->
            val idx = pair.indexOf('=')
            if (idx >= 0) urlDecode(pair.substring(0, idx)) to urlDecode(pair.substring(idx + 1))
            else urlDecode(pair) to ""
        }
    }

    private fun urlDecode(s: String): String =
        try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }

    private fun urlEncode(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (e: Exception) { s }

    fun normalizeUrl(rawUrl: String): String {
        val parts = split(rawUrl)
        val scheme = (parts.scheme.ifEmpty { "http" }).lowercase()
        var netloc = parts.netloc.lowercase()

        var userinfo: String? = null
        val atIdx = netloc.lastIndexOf('@')
        if (atIdx >= 0) { userinfo = netloc.substring(0, atIdx); netloc = netloc.substring(atIdx + 1) }

        val colonIdx = netloc.indexOf(':')
        if (colonIdx >= 0) {
            val host = netloc.substring(0, colonIdx)
            val port = netloc.substring(colonIdx + 1)
            netloc = if (DEFAULT_PORTS[scheme] == port) host else "$host:$port"
        }
        if (userinfo != null) netloc = "$userinfo@$netloc"

        var path = parts.path.ifEmpty { "/" }
        if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)

        val queryPairs = parseQueryPairs(parts.query).sortedWith(compareBy({ it.first }, { it.second }))
        val query = queryPairs.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }

        val sb = StringBuilder()
        sb.append(scheme).append("://").append(netloc).append(path)
        if (query.isNotEmpty()) sb.append('?').append(query)
        return sb.toString()
    }

    fun urlKey(url: String): String {
        val norm = normalizeUrl(url)
        val parts = split(norm)
        val host = parts.netloc.substringBefore(':')
        val reversedHost = host.split(".").reversed().joinToString(",")
        val q = if (parts.query.isNotEmpty()) "?${parts.query}" else ""
        return "$reversedHost)${parts.path}$q"
    }

    fun fingerprint(text: String): String {
        val normalized = WS_RE.replace(text, " ").trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun normalizeText(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return WS_RE.replace(nfkc, " ").trim()
    }

    fun normalizeQuery(rawQuery: String): String = normalizeText(rawQuery).lowercase()

    fun tokenize(text: String): List<String> = TOKEN_RE.findAll(text.lowercase()).map { it.value }.toList()
}
