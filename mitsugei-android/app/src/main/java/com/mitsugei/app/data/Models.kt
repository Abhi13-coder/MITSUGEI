package com.mitsugei.app.data

data class SearchResult(
    val title: String,
    val url: String,
    val displayUrl: String,
    val snippet: String,
    val faviconUrl: String? = null,
    val imageUrl: String? = null,
    val source: String = "web"
)

data class SearchResponse(
    val query: String,
    val results: List<SearchResult>,
    val tookMs: Long = 0,
    val error: String? = null
)
