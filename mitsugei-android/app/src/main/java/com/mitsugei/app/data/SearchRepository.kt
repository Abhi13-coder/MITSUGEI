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
