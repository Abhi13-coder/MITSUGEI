package com.mitsugei.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.net.URI

/**
 * Port of storage/schema.py + storage/sqlite.py.
 *
 * v2 restores the original adaptive position encoder (delta / bit-pack /
 * affine / dict / raw-fallback) that the first Kotlin port deliberately
 * dropped.  Positions are stored as a BLOB produced by
 * [AdaptiveEncoder]; ranking of user history can be performed by
 * [BitRanker] on the compressed bytes alone — decompression happens only
 * when materialising results for the human.
 *
 * Schema uses primary keys + candidate unique constraints + B-tree indexes
 * (SQLite default) and is already in 3NF for the inverted-index tables,
 * giving free de-duplication of terms and fingerprints.
 *
 * Every upsert here is written as "try UPDATE, INSERT if 0 rows changed"
 * rather than SQLite's `ON CONFLICT ... DO UPDATE` syntax, because that
 * UPSERT syntax needs SQLite >= 3.24 (2018) and Android 8.0 (API 26,
 * this app's minSdk) can ship an older bundled SQLite than that on some
 * OEM builds. The manual form works on every API level this app targets.
 */
class MitsugeiDb(context: Context, dbName: String = "mitsugei.db") :
    SQLiteOpenHelper(context.applicationContext, dbName, null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE documents (
                doc_id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                urlkey TEXT NOT NULL,
                title TEXT, text TEXT, language TEXT, fingerprint TEXT,
                crawl_timestamp TEXT, dump TEXT, length INTEGER, added_at REAL
            )""",
        )
        db.execSQL("CREATE INDEX idx_documents_urlkey ON documents(urlkey)")
        db.execSQL("CREATE INDEX idx_documents_fingerprint ON documents(fingerprint)")
        db.execSQL("CREATE TABLE terms (term_id INTEGER PRIMARY KEY AUTOINCREMENT, term TEXT NOT NULL UNIQUE, doc_freq INTEGER NOT NULL DEFAULT 0)")
        db.execSQL(
            """CREATE TABLE postings (
                term_id INTEGER NOT NULL,
                doc_id INTEGER NOT NULL,
                term_freq INTEGER NOT NULL,
                field TEXT NOT NULL DEFAULT 'body',
                positions BLOB,
                PRIMARY KEY (term_id, doc_id, field)
            )""",
        )
        db.execSQL("CREATE INDEX idx_postings_doc ON postings(doc_id)")
        // covering index for the common “give me all postings of a term” path
        db.execSQL("CREATE INDEX idx_postings_term_field ON postings(term_id, field)")
        db.execSQL("CREATE TABLE query_stats (query_norm TEXT PRIMARY KEY, count INTEGER NOT NULL DEFAULT 0, last_seen REAL)")
        db.execSQL("CREATE TABLE domain_stats (domain TEXT PRIMARY KEY, doc_count INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE fingerprints (fingerprint TEXT PRIMARY KEY, doc_id INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE markov_states (state TEXT PRIMARY KEY, total_count INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE markov_transitions (from_state TEXT NOT NULL, to_state TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (from_state, to_state))")
        db.execSQL("CREATE TABLE rank_brain_weights (signal_name TEXT PRIMARY KEY, weight REAL NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // add the positions BLOB; existing rows keep NULL positions
            db.execSQL("ALTER TABLE postings ADD COLUMN positions BLOB")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_postings_term_field ON postings(term_id, field)")
        }
    }

    // -- ingestion: add a document + build its postings ------------------

    data class DocumentRow(
        val docId: Long, val url: String, val urlkey: String, val title: String?, val text: String?,
        val language: String?, val crawlTimestamp: String?, val dump: String?,
    )

    fun addDocument(
        url: String, title: String, text: String, language: String? = null,
        crawlTimestamp: String? = null, dump: String? = null,
    ): Long? {
        val db = writableDatabase
        val normUrl = Normalization.normalizeUrl(url)
        val urlkey = Normalization.urlKey(url)
        val fp = Normalization.fingerprint(text)

        db.rawQuery("SELECT doc_id FROM fingerprints WHERE fingerprint = ?", arrayOf(fp)).use {
            if (it.moveToFirst()) return null // duplicate content, skip
        }

        val cv = ContentValues().apply {
            put("url", normUrl); put("urlkey", urlkey); put("title", title); put("text", text)
            put("language", language); put("fingerprint", fp); put("crawl_timestamp", crawlTimestamp)
            put("dump", dump); put("length", text.length); put("added_at", System.currentTimeMillis() / 1000.0)
        }
        val docId = db.insertWithOnConflict("documents", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (docId == -1L) return null // URL already present

        db.insert("fingerprints", null, ContentValues().apply { put("fingerprint", fp); put("doc_id", docId) })

        indexField(docId, "title", title)
        indexField(docId, "body", text)
        indexField(docId, "url", normUrl)

        bumpDomainCount(hostOf(normUrl))
        return docId
    }

    private fun bumpDomainCount(domain: String) {
        if (domain.isEmpty()) return
        val db = writableDatabase
        val exists = db.rawQuery("SELECT 1 FROM domain_stats WHERE domain = ?", arrayOf(domain)).use { it.moveToFirst() }
        if (exists) {
            db.execSQL("UPDATE domain_stats SET doc_count = doc_count + 1 WHERE domain = ?", arrayOf(domain))
        } else {
            db.insert("domain_stats", null, ContentValues().apply { put("domain", domain); put("doc_count", 1) })
        }
    }

    private fun indexField(docId: Long, field: String, text: String?) {
        val tokens = Normalization.tokenize(text ?: "")
        if (tokens.isEmpty()) return
        val db = writableDatabase

        // collect both frequency and sorted position lists
        val freq = LinkedHashMap<String, Int>()
        val positions = LinkedHashMap<String, MutableList<Int>>()
        tokens.forEachIndexed { idx, tok ->
            freq[tok] = (freq[tok] ?: 0) + 1
            positions.getOrPut(tok) { ArrayList() }.add(idx)
        }

        for ((term, count) in freq) {
            val termId = getOrCreateTerm(term)
            val posList = positions[term]!!.toIntArray()
            // AdaptiveEncoder tries delta / affine / dict / bitpack / raw and
            // keeps the smallest; if nothing compresses it stores RAW.
            val encoded = AdaptiveEncoder.encode(posList)

            val cv = ContentValues().apply {
                put("term_freq", count)
                put("positions", encoded)
            }
            val updated = db.update(
                "postings", cv,
                "term_id = ? AND doc_id = ? AND field = ?",
                arrayOf(termId.toString(), docId.toString(), field),
            )
            if (updated == 0) {
                db.insert(
                    "postings", null,
                    ContentValues().apply {
                        put("term_id", termId)
                        put("doc_id", docId)
                        put("term_freq", count)
                        put("field", field)
                        put("positions", encoded)
                    },
                )
            }
        }
    }

    private fun getOrCreateTerm(term: String): Long {
        val db = writableDatabase
        db.rawQuery("SELECT term_id FROM terms WHERE term = ?", arrayOf(term)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                db.execSQL("UPDATE terms SET doc_freq = doc_freq + 1 WHERE term_id = ?", arrayOf(id.toString()))
                return id
            }
        }
        return db.insert("terms", null, ContentValues().apply { put("term", term); put("doc_freq", 1) })
    }

    // -- candidate discovery: local inverted-index lookup -----------------

    fun lookupCandidates(terms: List<String>, field: String = "body", limit: Int = 500): List<Long> {
        val distinct = terms.distinct()
        if (distinct.isEmpty()) return emptyList()
        val placeholders = distinct.joinToString(",") { "?" }
        val args = (distinct + field).toTypedArray()
        val db = readableDatabase
        val out = ArrayList<Long>()
        db.rawQuery(
            """SELECT p.doc_id, SUM(p.term_freq) as hits
               FROM postings p JOIN terms t ON p.term_id = t.term_id
               WHERE t.term IN ($placeholders) AND p.field = ?
               GROUP BY p.doc_id ORDER BY hits DESC LIMIT $limit""",
            args,
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    fun termFreq(docId: Long, term: String, field: String = "body"): Int {
        val db = readableDatabase
        db.rawQuery(
            """SELECT p.term_freq FROM postings p JOIN terms t ON p.term_id = t.term_id
               WHERE p.doc_id = ? AND t.term = ? AND p.field = ?""",
            arrayOf(docId.toString(), term, field),
        ).use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }

    /**
     * Return the *decoded* position list for a term in a document.
     * Prefer [termPositionsCompressed] + [BitRanker] when you only need to rank.
     */
    fun termPositions(docId: Long, term: String, field: String = "body"): IntArray {
        val blob = termPositionsCompressed(docId, term, field) ?: return IntArray(0)
        return AdaptiveEncoder.decode(blob)
    }

    /**
     * Raw AdaptiveEncoder blob — never decompresses.  Suitable for BitRanker.
     */
    fun termPositionsCompressed(docId: Long, term: String, field: String = "body"): ByteArray? {
        readableDatabase.rawQuery(
            """SELECT p.positions FROM postings p JOIN terms t ON p.term_id = t.term_id
               WHERE p.doc_id = ? AND t.term = ? AND p.field = ?""",
            arrayOf(docId.toString(), term, field),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getBlob(0)
        }
    }

    /**
     * Rank a user's history (all postings that touch any of [terms]) using
     * only compressed bit metadata — no decompression until the caller
     * decides to materialise a result via [BitRanker.materialise].
     */
    fun rankHistoryByBits(
        terms: List<String>,
        field: String = "body",
        limit: Int = 200,
        recencyWeights: Map<Long, Double> = emptyMap(),
    ): List<BitRanker.RankedItem> {
        val distinct = terms.distinct()
        if (distinct.isEmpty()) return emptyList()
        val placeholders = distinct.joinToString(",") { "?" }
        val args = (distinct + field).toTypedArray()
        val items = ArrayList<Triple<Long, Int, ByteArray>>()
        readableDatabase.rawQuery(
            """SELECT p.doc_id, p.term_freq, p.positions
               FROM postings p JOIN terms t ON p.term_id = t.term_id
               WHERE t.term IN ($placeholders) AND p.field = ? AND p.positions IS NOT NULL
               LIMIT $limit""",
            args,
        ).use { c ->
            while (c.moveToNext()) {
                val docId = c.getLong(0)
                val tf = c.getInt(1)
                val blob = c.getBlob(2) ?: continue
                items.add(Triple(docId, tf, blob))
            }
        }
        return BitRanker.rank(items, recencyWeights = recencyWeights)
    }

    fun docFreq(term: String): Int {
        readableDatabase.rawQuery("SELECT doc_freq FROM terms WHERE term = ?", arrayOf(term)).use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    fun totalDocuments(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM documents", null).use { it.moveToFirst(); return it.getInt(0) }
    }

    fun getDocument(docId: Long): DocumentRow? {
        readableDatabase.rawQuery("SELECT * FROM documents WHERE doc_id = ?", arrayOf(docId.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return DocumentRow(
                docId = c.getLong(c.getColumnIndexOrThrow("doc_id")),
                url = c.getString(c.getColumnIndexOrThrow("url")),
                urlkey = c.getString(c.getColumnIndexOrThrow("urlkey")),
                title = c.getString(c.getColumnIndexOrThrow("title")),
                text = c.getString(c.getColumnIndexOrThrow("text")),
                language = c.getString(c.getColumnIndexOrThrow("language")),
                crawlTimestamp = c.getString(c.getColumnIndexOrThrow("crawl_timestamp")),
                dump = c.getString(c.getColumnIndexOrThrow("dump")),
            )
        }
    }

    fun domainAuthority(domain: String): Double {
        readableDatabase.rawQuery("SELECT doc_count FROM domain_stats WHERE domain = ?", arrayOf(domain)).use { c ->
            if (!c.moveToFirst()) return 0.0
            val count = c.getInt(0)
            return minOf(1.0, Math.log1p(count.toDouble()) / 5.0)
        }
    }

    private fun hostOf(url: String): String = try { URI(url).host ?: "" } catch (e: Exception) { "" }

    // -- query/ranking stats ----------------------------------------------

    fun recordQuery(normalizedQuery: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis() / 1000.0
        val exists = db.rawQuery("SELECT 1 FROM query_stats WHERE query_norm = ?", arrayOf(normalizedQuery)).use { it.moveToFirst() }
        if (exists) {
            db.execSQL("UPDATE query_stats SET count = count + 1, last_seen = ? WHERE query_norm = ?", arrayOf(now, normalizedQuery))
        } else {
            db.insert("query_stats", null, ContentValues().apply { put("query_norm", normalizedQuery); put("count", 1); put("last_seen", now) })
        }
    }

    fun getRankBrainWeights(): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        readableDatabase.rawQuery("SELECT signal_name, weight FROM rank_brain_weights", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getDouble(1)
        }
        return out
    }

    fun setRankBrainWeight(signalName: String, weight: Double) {
        val db = writableDatabase
        val updated = db.update("rank_brain_weights", ContentValues().apply { put("weight", weight) }, "signal_name = ?", arrayOf(signalName))
        if (updated == 0) db.insert("rank_brain_weights", null, ContentValues().apply { put("signal_name", signalName); put("weight", weight) })
    }

    // -- markov -------------------------------------------------------------

    fun markovBump(fromState: String, toState: String) {
        val db = writableDatabase
        val stateExists = db.rawQuery("SELECT 1 FROM markov_states WHERE state = ?", arrayOf(fromState)).use { it.moveToFirst() }
        if (stateExists) db.execSQL("UPDATE markov_states SET total_count = total_count + 1 WHERE state = ?", arrayOf(fromState))
        else db.insert("markov_states", null, ContentValues().apply { put("state", fromState); put("total_count", 1) })

        val transExists = db.rawQuery(
            "SELECT 1 FROM markov_transitions WHERE from_state = ? AND to_state = ?",
            arrayOf(fromState, toState),
        ).use { it.moveToFirst() }
        if (transExists) {
            db.execSQL("UPDATE markov_transitions SET count = count + 1 WHERE from_state = ? AND to_state = ?", arrayOf(fromState, toState))
        } else {
            db.insert("markov_transitions", null, ContentValues().apply { put("from_state", fromState); put("to_state", toState); put("count", 1) })
        }
    }

    fun markovTransitions(fromState: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT to_state, count FROM markov_transitions WHERE from_state = ? ORDER BY count DESC",
            arrayOf(fromState),
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1)) }
        return out
    }

    fun markovTotal(fromState: String): Int {
        readableDatabase.rawQuery("SELECT total_count FROM markov_states WHERE state = ?", arrayOf(fromState)).use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }
}
