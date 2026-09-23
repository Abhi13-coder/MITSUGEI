package com.mitsugei.app.data

import android.content.Context
import com.mitsugei.engine.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Mitsugei search — no server, no HTTP round-trip to a backend.
 *
 * Replaces the previous HTTP client (which called a FastAPI server at
 * 10.0.2.2:8000 / a LAN IP / a hosted URL — none of which exist anymore).
 * SearchEngine (com.mitsugei.engine) runs the whole pipeline in-process:
 * local SQLite cache -> verticals -> mitsugeidb lakes -> ranking -> snippets.
 *
 * hfToken/githubToken are optional — pass a Hugging Face token if you hit
 * rate limits on gated/high-traffic repos, and a GitHub token to raise the
 * unauthenticated search-API rate limit (60/hr -> much higher). Both are
 * fine as null for casual use.
 */
class SearchRepository(
    context: Context,
    hfToken: String? = null,
    githubToken: String? = null,
) {
    private val engine = SearchEngine(
        context = context.applicationContext,
        hfToken = hfToken,
        githubToken = githubToken,
    )

    suspend fun search(query: String, limit: Int = 20): SearchResponse = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SearchResponse(q, emptyList())

        // Direct-navigation shortcut: if what was typed/pasted is itself a
        // URL, don't run it through tokenize -> lake-term-match at all —
        // that pipeline strips the scheme/path apart into search terms and
        // then ranks whatever text happens to match those terms, which is
        // almost never the page the user actually pasted. Resolve to the
        // exact address instead, same as a browser omnibox would.
        asUrl(q)?.let { exact ->
            return@withContext SearchResponse(
                query = q,
                results = listOf(
                    SearchResult(
                        title = exact,
                        url = exact,
                        displayUrl = prettyUrl(exact),
                        snippet = "Open this address directly",
                        source = "direct",
                    ),
                ),
            )
        }

        val start = System.currentTimeMillis()
        try {
            val results = engine.search(q, topK = limit).map { r ->
                SearchResult(
                    title = r.title.ifBlank { r.url },
                    url = r.url,
                    displayUrl = prettyUrl(r.url),
                    snippet = r.snippet,
                    source = "web",
                )
            }
            SearchResponse(query = q, results = results, tookMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            SearchResponse(
                query = q, results = emptyList(),
                error = "Search failed (${e.javaClass.simpleName}: ${e.message})",
            )
        }
    }

    // Recognizes "example.com", "www.example.com/path", "http(s)://…" as a
    // navigation target rather than a text query. Deliberately conservative:
    // requires a dotted host with a plausible TLD (or an explicit scheme),
    // so ordinary multi-word queries never get misrouted here.
    private val bareHostPattern = Regex(
        "^(https?://)?([a-z0-9-]+\\.)+[a-z]{2,}(:\\d+)?(/\\S*)?$",
        RegexOption.IGNORE_CASE,
    )

    private fun asUrl(q: String): String? {
        if (q.contains(' ')) return null
        if (!bareHostPattern.matches(q)) return null
        val withScheme = if (q.startsWith("http://") || q.startsWith("https://")) q else "https://$q"
        return try {
            val u = java.net.URI(withScheme)
            if (u.host.isNullOrBlank()) null else withScheme
        } catch (e: Exception) {
            null
        }
    }

    private fun prettyUrl(url: String): String {
        return try {
            val u = java.net.URI(url)
            val host = u.host?.removePrefix("www.") ?: url
            val path = u.path?.trimEnd('/')?.take(48) ?: ""
            if (path.isBlank() || path == "/") host
            else "$host › ${path.trimStart('/').replace("/", " › ")}"
        } catch (e: Exception) {
            url
        }
    }
}
