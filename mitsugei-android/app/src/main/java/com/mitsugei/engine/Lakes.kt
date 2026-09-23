package com.mitsugei.engine

/**
 * Port of mitsugei/retrieval/lakes.py. Pure metadata — MitsugeiDbLake
 * (com.mitsugei.mitsugeidb) does the actual HTTP-range Parquet reading.
 * See that package for why this doesn't go through datasets-server's
 * /search: that endpoint permanently caps indexing at the first 5GB of
 * anything bigger, silently.
 */
data class LakePreset(
    val dataset: String,
    val revision: String = "refs/convert/parquet",
    val subpathPrefix: String = "",
    val urlCol: String = "url",
    val textCol: String = "text",
    val dumpCol: String? = "dump",
    val dateCol: String? = "date",
    val notes: String = "",
)

object Lakes {
    val LAKES: Map<String, LakePreset> = mapOf(
        "fineweb-sample-10bt" to LakePreset(
            dataset = "HuggingFaceFW/fineweb", subpathPrefix = "sample-10BT/train/",
            notes = "~28.5 GB, 14 shards. Best first lake to point at.",
        ),
        "fineweb-sample-100bt" to LakePreset(
            dataset = "HuggingFaceFW/fineweb", subpathPrefix = "sample-100BT/train/",
            notes = "~286 GB. Broader coverage, more shards to walk on a miss.",
        ),
        "fineweb-full" to LakePreset(
            dataset = "HuggingFaceFW/fineweb", subpathPrefix = "",
            notes = "~4.5 TB across ~96 dumps. No 5GB cap now, but a rare term walks a lot of shards.",
        ),
        "fineweb-edu-sample-10bt" to LakePreset(
            dataset = "HuggingFaceFW/fineweb-edu", subpathPrefix = "sample-10BT/train/", dateCol = null,
            notes = "Educational filter.",
        ),
        "fineweb-edu-full" to LakePreset(
            dataset = "HuggingFaceFW/fineweb-edu", subpathPrefix = "", dateCol = null,
            notes = "~4.5 TB educational subset, all dumps.",
        ),
        "fineweb2-hindi" to LakePreset(
            dataset = "HuggingFaceFW/fineweb-2", subpathPrefix = "hin_Deva/train/", notes = "Hindi (Devanagari).",
        ),
        "fineweb2-telugu" to LakePreset(
            dataset = "HuggingFaceFW/fineweb-2", subpathPrefix = "tel_Telu/train/", notes = "Telugu.",
        ),
        "c4-en" to LakePreset(
            dataset = "allenai/c4", subpathPrefix = "en/", dumpCol = null, dateCol = "timestamp",
            notes = "English C4 (2019 snapshot).",
        ),
        "c4-hindi" to LakePreset(
            dataset = "allenai/c4", subpathPrefix = "hi/", dumpCol = null, dateCol = "timestamp",
            notes = "mC4 Hindi.",
        ),
        "c4-telugu" to LakePreset(
            dataset = "allenai/c4", subpathPrefix = "te/", dumpCol = null, dateCol = "timestamp",
            notes = "mC4 Telugu.",
        ),
        "open-markdown" to LakePreset(
            dataset = "open-index/open-markdown", subpathPrefix = "data/CC-MAIN-2026-12/",
            textCol = "markdown", dumpCol = null, dateCol = "crawl_date",
            notes = "~3.4GB total (one crawl snapshot) — small enough for full coverage.",
        ),
        // "open-markdown-v2": not added — no repo by that name confirmed on the Hub yet.
        "refinedweb" to LakePreset(
            dataset = "tiiuae/falcon-refinedweb", subpathPrefix = "default/train/", textCol = "content",
            notes = "~600B tokens, high-quality filtered CC.",
        ),
        "refinedweb-sample" to LakePreset(
            dataset = "tiiuae/falcon-refinedweb", subpathPrefix = "default/train/000", textCol = "content",
            notes = "Smaller RefinedWeb slice via a narrower shard prefix.",
        ),
        "openwebtext2" to LakePreset(
            dataset = "Geralt-Targaryen/openwebtext2", subpathPrefix = "webtext2-00001-of-00027.parquet",
            urlCol = "title", dumpCol = null, dateCol = null,
            notes = "OpenWebText2 cleaned. Single shard on purpose.",
        ),
        "arxiv-papers" to LakePreset(
            dataset = "secemp9/arxiv-complete", subpathPrefix = "metadata/",
            urlCol = "paper_id", textCol = "title", dumpCol = null, dateCol = null,
            notes = "arXiv metadata.",
        ),
        "rstar-coder" to LakePreset(
            dataset = "microsoft/rStar-Coder", subpathPrefix = "synthetic_sft/data-00000-of-00015.parquet",
            urlCol = "seed_question", textCol = "question", dumpCol = null, dateCol = null,
            notes = "rStar-Coder competitive code. Single shard.",
        ),
        "cc-creativecommons" to LakePreset(
            dataset = "BramVanroy/CommonCrawl-CreativeCommons", subpathPrefix = "",
            notes = "License-tagged CommonCrawl text, boilerplate already stripped.",
        ),
    )
}
