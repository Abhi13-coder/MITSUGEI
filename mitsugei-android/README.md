# Mitsugei Android

Google-style search client, entirely on-device — no server, no Python, no
Chaquopy. Two Kotlin packages do the whole job:

- **`com.mitsugei.mitsugeidb`** — reads Parquet lakes (FineWeb, RefinedWeb,
  C4, open-markdown, …) directly off Hugging Face's CDN via HTTP range
  requests: fetches the footer, decodes it (hand-rolled Thrift Compact
  Protocol reader), fetches only the matching column chunks, decompresses
  (Snappy/Zstd), decodes PLAIN/dictionary-encoded string pages, and
  regex-matches. No DuckDB, no server-side index, no 5GB coverage cap.
- **`com.mitsugei.engine`** — everything else: query parsing, a local
  SQLite inverted index, live vertical APIs (Wikipedia/GitHub/HF/Stack
  Exchange/Reddit), a Markov transition model, Page Ranker feature
  scoring, Rank Brain's learned re-weighting, and snippet extraction.

This used to be a Python engine (FastAPI server + DuckDB+httpfs), ported
to Kotlin because Chaquopy dropped 32-bit ARM (`armeabi-v7a`) support and
DuckDB/PyArrow never shipped `armeabi-v7a` wheels at all — both dead ends
for a 32-bit device. Straight Kotlin/JVM has no such gap.

## Requirements

- **minSdk 26** (Android 8.0 Oreo)
- **targetSdk 36** (Android 16 — mandatory for Play submissions since Aug 31 2026)
- JDK 17
- Android Studio (current) or CI via your own GitHub Actions workflow (none
  is bundled here on purpose — wire one up if you want automated builds)

## Build locally

```bash
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

## How a search actually runs

```
query -> local SQLite cache (fast path once you've searched something before)
      -> verticals (Wikipedia / GitHub / HF / StackExchange / Reddit) — intent-matched
      -> mitsugeidb lakes, in router order (open-markdown first — the one
         lake small enough for genuinely full coverage; then FineWeb-Edu,
         RefinedWeb, C4, … each capped only by how many shards a query has
         to walk, not by a fixed index)
      -> Page Ranker scores every candidate (title/url/body match, phrase
         match, proximity, freshness, domain authority, TF-IDF, a cheap
         subword-hash "semantic" signal)
      -> Rank Brain applies learned per-signal multipliers (starts at 1.0
         — no-op — until it has click feedback to learn from)
      -> Markov's transition-probability score gets folded in
      -> top K get a highlighted snippet and go back to the UI
```

Everything ingested along the way (lake hits, vertical hits) is written
into the local SQLite index, so the *next* search for anything overlapping
hits the fast local path first.

## Adaptive position encoder (restored in v2)

Per-term token positions are again stored, using
`com.mitsugei.engine.AdaptiveEncoder`:

- tries **delta + bit-pack**, **affine (FOR) + bit-pack**, **delta-affine**,
  **dictionary**, **plain bit-pack**, and falls back to **raw** 32-bit ints
  when nothing compresses better
- all operations are pure integer shifts / masks (CPU bit-instruction level)
- blobs live in the `postings.positions` BLOB column
- SQLite schema stays 3NF with primary / unique candidate keys and B-tree
  indexes → automatic de-duplication of terms & fingerprints

**BitRanker** can rank a user's history *without ever decompressing* the
blobs (it only reads the codec tag + header metadata). Decompression
happens only when a ranked item is materialised for the human UI via
`BitRanker.materialise` / `MitsugeiDb.termPositions`.

Proximity scoring in the live path still re-tokenises at query time for
simplicity; stored positions are available for any future feature that
wants them.

## What's a known simplification, not a bug

- **mitsugeidb supports DataPageV1 with PLAIN / dictionary-encoded
  BYTE_ARRAY columns only** — the shape every lake's `url`/`text`/`dump`/
  `date` columns actually have. DataPageV2 and DELTA_* encodings aren't
  implemented; a row group hitting one of those is skipped, not
  mis-parsed.
- **`open-markdown-v2`** isn't in the lake registry — no repository by
  that name was found on the Hub. Send the exact repo id if you have one.

## Tokens (optional)

`SearchRepository(context, hfToken = "...", githubToken = "...")` — a HF
token helps if you hit rate limits on gated/high-traffic repos; a GitHub
token raises the unauthenticated search API's 60/hr limit. Both are fine
left `null` for casual use.
