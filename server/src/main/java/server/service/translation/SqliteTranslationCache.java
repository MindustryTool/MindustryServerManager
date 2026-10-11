package server.service.translation;

import java.util.List;
import java.util.Optional;

import arc.util.Log;
import common.translation.TranslationResponse;
import plugin.orm.Row;
import plugin.orm.SQLiteDatabase;
import plugin.orm.table.Column;
import plugin.orm.table.Table;

/**
 * Persistent L2 translation cache backed by embedded SQLite.
 * Uses typed {@link SQLiteDatabase} API for CRUD; raw SQL remains only
 * for PRAGMA WAL, index create, count, and LRU delete.
 * Single {@code updated_at} column tracks last write or read for TTL/LRU.
 */
public class SqliteTranslationCache implements AutoCloseable {

    public static final long TTL_MILLIS = 30L * 24 * 60 * 60 * 1000;
    public static final int MAX_ROWS = 50_000;

    private static final Table<Void> TABLE = Table.of("translation_cache");
    private static final Column<String> CACHE_KEY = TABLE.column("cache_key", String.class).primaryKey();
    private static final Column<String> TARGET_LANG = TABLE.column("target_lang", String.class).notNull();
    private static final Column<String> SOURCE_LANG = TABLE.column("source_lang", String.class);
    private static final Column<String> SOURCE_TEXT = TABLE.column("source_text", String.class).notNull();
    private static final Column<String> TRANSLATED_TEXT = TABLE.column("translated_text", String.class).notNull();
    private static final Column<Long> UPDATED_AT = TABLE.column("updated_at", Long.class).notNull();

    private final SQLiteDatabase db;
    private final String path;

    public SqliteTranslationCache(String dbPath) {
        this.path = dbPath;
        this.db = SQLiteDatabase.builder().path(dbPath).build();
        init();
    }

    SqliteTranslationCache(SQLiteDatabase db, String path) {
        this.db = db;
        this.path = path;
        init();
    }

    private void init() {
        try {
            db.raw("PRAGMA journal_mode=WAL;");
        } catch (Exception e) {
            Log.warn("Failed to enable WAL for translation cache: @", e.getMessage());
        }
        db.createTableIfNotExists(TABLE, CACHE_KEY, TARGET_LANG, SOURCE_LANG, SOURCE_TEXT,
                TRANSLATED_TEXT, UPDATED_AT);
        db.raw("CREATE INDEX IF NOT EXISTS idx_translation_cache_updated_at "
                + "ON translation_cache(updated_at)");
        purgeExpired(System.currentTimeMillis() - TTL_MILLIS);
    }

    /** Lookup by cache key; on hit the entry timestamp is refreshed. */
    public synchronized Optional<TranslationResponse> get(String cacheKey) {
        Optional<Row> found = db.select(TARGET_LANG, SOURCE_LANG, SOURCE_TEXT, TRANSLATED_TEXT)
                .from(TABLE)
                .where(CACHE_KEY.eq(cacheKey))
                .fetchOne();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Row row = found.get();
        db.update(TABLE).set(UPDATED_AT, System.currentTimeMillis()).where(CACHE_KEY.eq(cacheKey)).execute();
        return Optional.of(new TranslationResponse(
                row.getString("translated_text"), row.getString("source_lang")));
    }

    /** Insert or replace; derives target_lang/source_text from key when possible. */
    public synchronized void put(String cacheKey, TranslationResponse response) {
        String targetLang = "";
        String sourceText = "";
        int sep = cacheKey.indexOf(':');
        if (sep >= 0) {
            targetLang = cacheKey.substring(0, sep);
            sourceText = cacheKey.substring(sep + 1);
        }
        put(cacheKey, targetLang, sourceText, response);
    }

    public synchronized void put(String cacheKey, String targetLang, String sourceText,
            TranslationResponse response) {
        long now = System.currentTimeMillis();
        db.insert(TABLE)
                .set(CACHE_KEY, cacheKey)
                .set(TARGET_LANG, targetLang)
                .set(SOURCE_LANG, response.getSourceLanguage())
                .set(SOURCE_TEXT, sourceText)
                .set(TRANSLATED_TEXT, response.getTranslatedText())
                .set(UPDATED_AT, now)
                .onConflictDoUpdate(CACHE_KEY, TARGET_LANG, SOURCE_LANG, SOURCE_TEXT, TRANSLATED_TEXT,
                        UPDATED_AT)
                .execute();
        enforceCapacity(MAX_ROWS);
    }

    /** Delete rows not touched since {@code cutoffMillis}. */
    public synchronized int purgeExpired(long cutoffMillis) {
        return db.delete(TABLE).where(UPDATED_AT.lt(cutoffMillis)).execute();
    }

    /** Delete least-recently used rows until count is within cap. */
    public synchronized int enforceCapacity(int maxRows) {
        List<Row> rows = db.rawQuery("SELECT COUNT(*) AS c FROM translation_cache");
        long count = rows.isEmpty() ? 0 : rows.get(0).getLong("c");
        if (count <= maxRows) {
            return 0;
        }
        long overflow = count - maxRows;
        return db.raw("DELETE FROM translation_cache WHERE cache_key IN "
                + "(SELECT cache_key FROM translation_cache ORDER BY updated_at ASC LIMIT ?)",
                overflow);
    }

    public synchronized long size() {
        List<Row> rows = db.rawQuery("SELECT COUNT(*) AS c FROM translation_cache");
        return rows.isEmpty() ? 0 : rows.get(0).getLong("c");
    }

    public String path() {
        return path;
    }

    @Override
    public void close() {
        db.close();
    }
}
