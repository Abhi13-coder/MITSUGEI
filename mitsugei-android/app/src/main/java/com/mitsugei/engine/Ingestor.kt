package com.mitsugei.engine

/** Port of the parts of mitsugei/ingest/ingestor.py the live search path
 * actually uses (_ingest_row, _derive_title). ingest_jsonl /
 * ingest_from_huggingface / ingest_from_commoncrawl aren't ported — they
 * were offline/CI bulk-ingestion helpers, not part of on-device search. */
object Ingestor {

    private fun deriveTitle(text: String, maxWords: Int = 12): String {
        val normalized = Normalization.normalizeText(text)
        if (normalized.isEmpty()) return ""
        val words = normalized.split(" ")
        val head = words.take(maxWords).joinToString(" ")
        return if (words.size > maxWords) "$head..." else head
    }

    fun ingestRow(
        db: MitsugeiDb, url: String, text: String, dump: String = "", date: String = "",
        title: String? = null, language: String? = null,
    ): Long? {
        if (url.isBlank() || text.isBlank() || text.trim().length < 20) return null
        return db.addDocument(
            url = url, title = title ?: deriveTitle(text), text = text,
            language = language, crawlTimestamp = date, dump = dump,
        )
    }
}
