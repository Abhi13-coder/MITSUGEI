package com.mitsugei.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Port of mitsugei/retrieval/verticals.py. */

data class VerticalHit(val url: String, val title: String, val text: String, val source: String, val score: Double = 1.0)

private val client = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun getJson(url: String, headers: Map<String, String> = emptyMap()): String? {
    return try {
        val builder = Request.Builder().url(url)
        for ((k, v) in headers) builder.addHeader(k, v)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        null
    }
}

interface Vertical {
    val name: String
    fun search(query: String, limit: Int = 20): List<VerticalHit>
}

object WikipediaVertical : Vertical {
    override val name = "wikipedia"
    private  val API = "https://en.wikipedia.org/w/api.php"
    private val tagRe = Regex("<[^>]+>")

    override fun search(query: String, limit: Int): List<VerticalHit> {
        val url = "$API?action=query&list=search&srsearch=${enc(query)}&srlimit=$limit&format=json&utf8=1"
        val body = getJson(url, mapOf("User-Agent" to "mitsugei/1.0 (research search prototype)")) ?: return emptyList()
        return try {
            val results = JSONObject(body).optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            (0 until results.length()).map { i ->
                val item = results.getJSONObject(i)
                val title = item.optString("title", "")
                val snippet = tagRe.replace(item.optString("snippet", ""), "")
                val pageUrl = "https://en.wikipedia.org/wiki/${enc(title.replace(' ', '_'))}"
                VerticalHit(pageUrl, title, "$title. $snippet", name)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class GitHubVertical(private val token: String? = null) : Vertical {
    override val name = "github"
    private  val API = "https://api.github.com/search/repositories"

    override fun search(query: String, limit: Int): List<VerticalHit> {
        val url = "$API?q=${enc(query)}&per_page=${minOf(limit, 30)}&sort=stars"
        val headers = HashMap<String, String>().apply {
            put("Accept", "application/vnd.github+json"); put("User-Agent", "mitsugei/1.0")
            token?.let { put("Authorization", "Bearer $it") }
        }
        val body = getJson(url, headers) ?: return emptyList()
        return try {
            val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
            (0 until minOf(items.length(), limit)).map { i ->
                val item = items.getJSONObject(i)
                val desc = item.optString("description", "")
                val text = "${item.optString("full_name", "")}. $desc. Language: ${item.optString("language", "n/a")}."
                VerticalHit(
                    item.optString("html_url", ""), item.optString("full_name", ""), text, name,
                    item.optLong("stargazers_count", 0) / 10000.0 + 0.5,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

object HuggingFaceVertical : Vertical {
    override val name = "huggingface"
    private  val API = "https://huggingface.co/api"

    override fun search(query: String, limit: Int): List<VerticalHit> {
        val hits = ArrayList<VerticalHit>()
        try {
            val body = getJson("$API/models?search=${enc(query)}&limit=$limit")
            if (body != null) {
                val models = JSONArray(body)
                for (i in 0 until minOf(models.length(), limit / 2 + 1)) {
                    val m = models.getJSONObject(i)
                    val modelId = if (m.has("modelId")) m.optString("modelId") else m.optString("id", "")
                    val tags = m.optJSONArray("tags")
                    val tagStr = tags?.let { (0 until minOf(it.length(), 5)).joinToString(", ") { j -> it.getString(j) } } ?: ""
                    hits.add(VerticalHit(
                        "https://huggingface.co/$modelId", modelId,
                        "Model: $modelId. Downloads: ${m.optLong("downloads", 0)}. Tags: $tagStr", name, 0.8,
                    ))
                }
            }
        } catch (e: Exception) { /* skip */ }
        try {
            val body = getJson("$API/datasets?search=${enc(query)}&limit=$limit")
            if (body != null) {
                val datasets = JSONArray(body)
                for (i in 0 until minOf(datasets.length(), limit / 2 + 1)) {
                    val d = datasets.getJSONObject(i)
                    val dsId = d.optString("id", "")
                    hits.add(VerticalHit(
                        "https://huggingface.co/datasets/$dsId", dsId,
                        "Dataset: $dsId. Downloads: ${d.optLong("downloads", 0)}.", name, 0.75,
                    ))
                }
            }
        } catch (e: Exception) { /* skip */ }
        return hits.take(limit)
    }
}

object StackExchangeVertical : Vertical {
    override val name = "stackexchange"
    private  val API = "https://api.stackexchange.com/2.3/search/advanced"

    override fun search(query: String, limit: Int): List<VerticalHit> {
        val url = "$API?order=desc&sort=relevance&q=${enc(query)}&site=stackoverflow&pagesize=${minOf(limit, 30)}&filter=default"
        val body = getJson(url) ?: return emptyList()
        return try {
            val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
            (0 until minOf(items.length(), limit)).map { i ->
                val item = items.getJSONObject(i)
                val title = item.optString("title", "")
                val tags = item.optJSONArray("tags")
                val tagStr = tags?.let { (0 until minOf(it.length(), 6)).joinToString(", ") { j -> it.getString(j) } } ?: ""
                val score = item.optInt("score", 0)
                VerticalHit(
                    item.optString("link", ""), title, "$title. Tags: $tagStr. Score: $score.", name,
                    minOf(1.0, score / 50.0 + 0.4),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

object RedditVertical : Vertical {
    override val name = "reddit"
    private  val API = "https://www.reddit.com/search.json"

    override fun search(query: String, limit: Int): List<VerticalHit> {
        val url = "$API?q=${enc(query)}&limit=${minOf(limit, 25)}&sort=relevance&t=year"
        val body = getJson(url, mapOf("User-Agent" to "mitsugei/1.0 (research search prototype)")) ?: return emptyList()
        return try {
            val children = JSONObject(body).optJSONObject("data")?.optJSONArray("children") ?: JSONArray()
            (0 until minOf(children.length(), limit)).map { i ->
                val post = children.getJSONObject(i).optJSONObject("data") ?: JSONObject()
                val title = post.optString("title", "")
                val selftext = post.optString("selftext", "").take(400)
                val permalink = post.optString("permalink", "")
                val score = post.optInt("score", 0)
                VerticalHit(
                    if (permalink.isNotEmpty()) "https://www.reddit.com$permalink" else "",
                    title, "$title. $selftext", name, minOf(1.0, score / 500.0 + 0.3),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** No stable public search API — kept so routing/interfaces stay uniform. */
object QuoraVertical : Vertical {
    override val name = "quora"
    override fun search(query: String, limit: Int): List<VerticalHit> = emptyList()
}

object Verticals {
    fun get(name: String, githubToken: String? = null): Vertical = when (name) {
        "wikipedia" -> WikipediaVertical
        "github" -> GitHubVertical(githubToken)
        "huggingface" -> HuggingFaceVertical
        "stackexchange" -> StackExchangeVertical
        "reddit" -> RedditVertical
        "quora" -> QuoraVertical
        else -> throw NoSuchElementException("unknown vertical $name")
    }

    fun list(): List<String> = listOf("github", "huggingface", "quora", "reddit", "stackexchange", "wikipedia")
}
