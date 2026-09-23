package com.mitsugei.engine

/** Port of mitsugei/retrieval/router.py. */
object Router {
    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
    private val TELUGU = Regex("[\\u0C00-\\u0C7F]")

    private val INTENT_PATTERNS: List<Pair<Regex, List<String>>> = listOf(
        Regex("\\b(wiki|wikipedia|who is|what is|history of|definition of)\\b", RegexOption.IGNORE_CASE) to listOf("wikipedia"),
        Regex("\\b(github|repo|repository|pull request|issue|readme|source code)\\b", RegexOption.IGNORE_CASE) to listOf("github"),
        Regex("\\b(huggingface|hf model|dataset|space|transformers|diffusers)\\b", RegexOption.IGNORE_CASE) to listOf("huggingface"),
        Regex("\\b(stackoverflow|stack overflow|stackexchange|error|exception|traceback|how to fix)\\b", RegexOption.IGNORE_CASE) to listOf("stackexchange"),
        Regex("\\b(reddit|subreddit|discussion|community|opinion|experience)\\b", RegexOption.IGNORE_CASE) to listOf("reddit"),
        Regex("\\b(quora|explain|why does|how come)\\b", RegexOption.IGNORE_CASE) to listOf("quora", "wikipedia"),
        Regex("\\b(python|javascript|typescript|rust|golang|java|c\\+\\+|sql|regex)\\b", RegexOption.IGNORE_CASE) to listOf("stackexchange", "github"),
    )

    fun detectScript(queryTerms: List<String>): String {
        val text = queryTerms.joinToString(" ")
        if (DEVANAGARI.containsMatchIn(text)) return "hindi"
        if (TELUGU.containsMatchIn(text)) return "telugu"
        return "latin"
    }

    // Precision/freshness-first, breadth-last. "open-markdown" leads the
    // latin chain because it's the one lake mitsugeidb can read with
    // genuinely full coverage in a couple of shards (~3.4GB total) rather
    // than having to walk deep into a multi-hundred-GB corpus first.
    val CHAINS: Map<String, List<String>> = mapOf(
        "latin" to listOf(
            "open-markdown",
            "fineweb-edu-sample-10bt",
            "refinedweb-sample",
            "fineweb-sample-10bt",
            "c4-en",
            "cc-creativecommons",
            "rstar-coder",
        ),
        "hindi" to listOf("fineweb2-hindi", "c4-hindi"),
        "telugu" to listOf("fineweb2-telugu", "c4-telugu"),
    )

    fun pickLakeChain(queryTerms: List<String>): List<String> = CHAINS[detectScript(queryTerms)] ?: emptyList()

    fun pickVerticals(query: String): List<String> {
        val matched = ArrayList<String>()
        val seen = HashSet<String>()
        for ((pattern, verts) in INTENT_PATTERNS) {
            if (pattern.containsMatchIn(query)) {
                for (v in verts) if (seen.add(v)) matched.add(v)
            }
        }
        return matched
    }
}
