package com.pocketorca.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/**
 * Local chat history store (M3/vc6, WeChat-style): SQLite in filesDir.
 * Truth source for the UI = this DB. Server session DB is the agent's memory,
 * not the app's transcript.
 *
 * vc15 / schema v3 (DB rebuild):
 *  - WAL journal mode (reader/writer stop blocking each other; replaying a long
 *    thread while a turn streams no longer stalls).
 *  - messages indexes on (cid,seq) and (ts); conv summary table (last text/ts/
 *    count) so the contact list never scans the message table.
 *  - upgrade path DROPS old tables and recreates (user decision 1101: 旧记录全丢
 *    无所谓，重建空库). A clean install therefore always starts empty.
 *  - page() for paging backwards; stats()/wipeAll() for the in-app data panel.
 *
 * Framework android.database.sqlite (no Gradle/androidx — PLAN §2 fallback).
 * Called from the JS bridge thread (never UI thread — WebViewBridge already
 * marshals off-main); all methods synchronized on the helper's single connection.
 */
final class ChatDB {
    private ChatDB() {}

    static final int SCHEMA = 4;
    private static final String DB_NAME = "chat.db";

    private static final Object LOCK = new Object();
    private static DBHelper DB;

    private static final class DBHelper extends SQLiteOpenHelper {
        DBHelper(Context c) { super(c, DB_NAME, null, SCHEMA); }

        @Override public void onConfigure(SQLiteDatabase db) {
            try { db.enableWriteAheadLogging(); } catch (Exception ignore) {}
        }

        @Override public void onCreate(SQLiteDatabase db) { createAll(db); }

        @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
            dropAll(db);      // vc15: no data migration, rebuild empty on purpose
            createAll(db);
        }

        @Override public void onDowngrade(SQLiteDatabase db, int o, int n) {
            dropAll(db);
            createAll(db);
        }

        private static void dropAll(SQLiteDatabase db) {
            String[] t = {"messages", "sessions", "conv", "seen", "sync"};
            for (int i = 0; i < t.length; i++) {
                try { db.execSQL("DROP TABLE IF EXISTS " + t[i]); } catch (Exception ignore) {}
            }
        }

        private static void createAll(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE messages ("
                    + "seq INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "cid TEXT NOT NULL, "
                    + "role TEXT NOT NULL, "            // user | bot | sys
                    + "text TEXT NOT NULL, "
                    + "ts INTEGER NOT NULL, "
                    + "mid TEXT)");
            db.execSQL("CREATE INDEX idx_msg_cid_seq ON messages(cid, seq)");
            db.execSQL("CREATE INDEX idx_msg_ts ON messages(ts)");
            db.execSQL("CREATE UNIQUE INDEX idx_msg_mid ON messages(cid, mid) WHERE mid IS NOT NULL");
            db.execSQL("CREATE TABLE sessions ("
                    + "cid TEXT PRIMARY KEY, "
                    + "session_id TEXT NOT NULL, "
                    + "fetched INTEGER DEFAULT 0, "
                    + "updated_ts INTEGER DEFAULT 0)");
            // vc15: per-contact summary — contact list renders from here, no table scan.
            db.execSQL("CREATE TABLE conv ("
                    + "cid TEXT PRIMARY KEY, "
                    + "last_role TEXT DEFAULT '', "
                    + "last_text TEXT DEFAULT '', "
                    + "last_ts INTEGER DEFAULT 0, "
                    + "msg_count INTEGER DEFAULT 0)");
            // vc16: server message ids already consumed by the poller. The PK is the
            // dedup guarantee — ChatDB.insert(mid) can never write the same server
            // message twice, no matter how many polls run.
            db.execSQL("CREATE TABLE seen ("
                    + "cid TEXT NOT NULL, "
                    + "sid TEXT NOT NULL, "
                    + "mid TEXT NOT NULL, "
                    + "PRIMARY KEY(cid, sid, mid))");
            // vc16: per-(contact, session) sync baseline marker.
            db.execSQL("CREATE TABLE sync ("
                    + "cid TEXT NOT NULL, "
                    + "sid TEXT NOT NULL, "
                    + "synced_ts INTEGER DEFAULT 0, "
                    + "PRIMARY KEY(cid, sid))");
        }
    }

    private static SQLiteDatabase db() {
        if (DB == null) DB = new DBHelper(Store.context());
        return DB.getWritableDatabase();
    }

    /** Append one message; returns row seq (<=0 on failure). */
    static long insert(String cid, String role, String text) { return insert(cid, role, text, ""); }

    /**
     * Append one message; returns row seq, or -1 when a non-empty mid was already
     * stored for this contact (server-side dedup, vc16).
     */
    static long insert(String cid, String role, String text, String mid) {
        if (cid == null || cid.isEmpty() || role == null || role.isEmpty()) return -1;
        String body = text == null ? "" : text;
        String key = mid == null ? "" : mid;
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("cid", cid);
        v.put("role", role);
        v.put("text", body);
        v.put("ts", now);
        if (!key.isEmpty()) v.put("mid", key);
        synchronized (LOCK) {
            try {
                SQLiteDatabase d = db();
                long seq = !key.isEmpty()
                        ? d.insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE)
                        : d.insert("messages", null, v);
                if (seq <= 0) return -1;
                touchConv(d, cid, role, body, now);
                return seq;
            } catch (Exception e) { return -1; }
        }
    }

    /**
     * vc16: does this contact already hold an identical message (same role+text)
     * inserted after sinceTs? The app writes its own turns into the DB when they
     * are sent/streamed; the poller would otherwise re-insert them as "new".
     */
    static boolean hasRecent(String cid, String role, String text, long sinceTs) {
        if (cid == null || cid.isEmpty() || text == null || text.isEmpty()) return false;
        synchronized (LOCK) {
            Cursor c = null;
            try {
                c = db().rawQuery(
                        "SELECT 1 FROM messages WHERE cid=? AND role=? AND text=? AND ts>=? LIMIT 1",
                        new String[]{cid, role, text, String.valueOf(sinceTs)});
                return c.moveToFirst();
            } catch (Exception ignore) { return false; }
            finally { if (c != null) c.close(); }
        }
    }

    /** vc16: claim a server message id. true = first time seen (caller should store it). */
    static boolean markSeen(String cid, String sid, String mid) {
        if (cid == null || cid.isEmpty() || sid == null || sid.isEmpty()
                || mid == null || mid.isEmpty()) return false;
        ContentValues v = new ContentValues();
        v.put("cid", cid);
        v.put("sid", sid);
        v.put("mid", mid);
        synchronized (LOCK) {
            try {
                return db().insertWithOnConflict("seen", null, v, SQLiteDatabase.CONFLICT_IGNORE) > 0;
            } catch (Exception e) { return false; }
        }
    }

    /** vc16: has the initial baseline for this contact/session been recorded? */
    static boolean isSynced(String cid, String sid) {
        if (cid == null || cid.isEmpty() || sid == null || sid.isEmpty()) return false;
        synchronized (LOCK) {
            Cursor c = null;
            try {
                c = db().rawQuery("SELECT 1 FROM sync WHERE cid=? AND sid=?", new String[]{cid, sid});
                return c.moveToFirst();
            } catch (Exception ignore) { return false; }
            finally { if (c != null) c.close(); }
        }
    }

    /** vc16: mark the baseline as recorded (history before this point is ignored). */
    static void markSynced(String cid, String sid) {
        if (cid == null || cid.isEmpty() || sid == null || sid.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("cid", cid);
        v.put("sid", sid);
        v.put("synced_ts", System.currentTimeMillis());
        synchronized (LOCK) {
            try { db().insertWithOnConflict("sync", null, v, SQLiteDatabase.CONFLICT_REPLACE); }
            catch (Exception ignore) {}
        }
    }

    /** Keep the conv summary in sync (UPDATE-then-INSERT: Android 10 ships
     *  SQLite 3.22, which has no ON CONFLICT DO UPDATE upsert). */
    private static void touchConv(SQLiteDatabase d, String cid, String role, String body, long ts) {
        try {
            ContentValues v = new ContentValues();
            v.put("last_role", role);
            v.put("last_text", body.length() > 80 ? body.substring(0, 80) : body);
            v.put("last_ts", ts);
            int n = d.update("conv", v, "cid=?", new String[]{cid});
            if (n == 0) {
                v.put("cid", cid);
                v.put("msg_count", 1);
                d.insertWithOnConflict("conv", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            } else {
                try { d.execSQL("UPDATE conv SET msg_count=msg_count+1 WHERE cid=?", new Object[]{cid}); }
                catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
    }

    /** Last N messages of a contact, oldest first, as [{seq,role,text,ts}]. */
    static String recent(String cid, int n) {
        return page(cid, Long.MAX_VALUE, n);
    }

    /** vc15: messages with seq < beforeSeq, oldest first. beforeSeq<=0 = latest page. */
    static String page(String cid, long beforeSeq, int limit) {
        JSONArray out = new JSONArray();
        if (cid == null || cid.isEmpty()) return out.toString();
        int lim = Math.max(1, limit);
        long before = beforeSeq <= 0 ? Long.MAX_VALUE : beforeSeq;
        synchronized (LOCK) {
            Cursor c = null;
            try {
                c = db().rawQuery(
                        "SELECT seq, role, text, ts FROM messages WHERE cid=? AND seq<? "
                                + "ORDER BY seq DESC LIMIT " + lim,
                        new String[]{cid, String.valueOf(before)});
                for (int i = c.getCount() - 1; i >= 0; i--) {
                    c.moveToPosition(i);
                    JSONObject m = new JSONObject();
                    m.put("seq", c.getLong(0));
                    m.put("role", c.getString(1));
                    m.put("text", c.getString(2));
                    m.put("ts", c.getLong(3));
                    out.put(m);
                }
            } catch (Exception ignore) {}
            finally { if (c != null) c.close(); }
        }
        return out.toString();
    }

    /** vc22: full-text search across the local transcript.
     *  cid=="" or null searches every contact. Returns [{seq,cid,role,text,ts}], newest first. */
    static String search(String q, String cid, int limit) {
        JSONArray out = new JSONArray();
        String kw = q == null ? "" : q.trim();
        if (kw.isEmpty()) return out.toString();
        int lim = Math.max(1, Math.min(500, limit));
        String esc = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        boolean scoped = cid != null && !cid.isEmpty();
        synchronized (LOCK) {
            Cursor c = null;
            try {
                String sql = "SELECT seq, cid, role, text, ts FROM messages WHERE text LIKE ? ESCAPE '\\'"
                        + (scoped ? " AND cid=?" : "")
                        + " ORDER BY ts DESC, seq DESC LIMIT " + lim;
                String[] args = scoped
                        ? new String[]{"%" + esc + "%", cid}
                        : new String[]{"%" + esc + "%"};
                c = db().rawQuery(sql, args);
                while (c.moveToNext()) {
                    JSONObject m = new JSONObject();
                    m.put("seq", c.getLong(0));
                    m.put("cid", c.getString(1));
                    m.put("role", c.getString(2));
                    m.put("text", c.getString(3));
                    m.put("ts", c.getLong(4));
                    out.put(m);
                }
            } catch (Exception ignore) {}
            finally { if (c != null) c.close(); }
        }
        return out.toString();
    }

    /** vc22: a window of messages around anchor seq (for jump-from-search).
     *  Returns [{seq,role,text,ts}] oldest first. */
    static String window(String cid, long anchor, int before, int after) {
        JSONArray out = new JSONArray();
        if (cid == null || cid.isEmpty() || anchor <= 0) return out.toString();
        long lo = anchor - Math.max(0, before);
        long hi = anchor + Math.max(0, after);
        synchronized (LOCK) {
            Cursor c = null;
            try {
                c = db().rawQuery(
                        "SELECT seq, role, text, ts FROM messages WHERE cid=? AND seq>=? AND seq<=? ORDER BY seq",
                        new String[]{cid, String.valueOf(lo), String.valueOf(hi)});
                while (c.moveToNext()) {
                    JSONObject m = new JSONObject();
                    m.put("seq", c.getLong(0));
                    m.put("role", c.getString(1));
                    m.put("text", c.getString(2));
                    m.put("ts", c.getLong(3));
                    out.put(m);
                }
            } catch (Exception ignore) {}
            finally { if (c != null) c.close(); }
        }
        return out.toString();
    }

    /** Message count of a contact (restore guard: pull server history only when 0). */
    static int count(String cid) {
        if (cid == null || cid.isEmpty()) return 0;
        synchronized (LOCK) {
            Cursor c = null;
            try {
                c = db().rawQuery("SELECT COUNT(*) FROM messages WHERE cid=?", new String[]{cid});
                if (c.moveToFirst()) return c.getInt(0);
            } catch (Exception ignore) {}
            finally { if (c != null) c.close(); }
        }
        return 0;
    }

    /** Delete all messages of a contact (used on contact delete). Returns row count. */
    static int clear(String cid) {
        if (cid == null || cid.isEmpty()) return 0;
        synchronized (LOCK) {
            try {
                SQLiteDatabase d = db();
                int n = d.delete("messages", "cid=?", new String[]{cid});
                try { d.delete("conv", "cid=?", new String[]{cid}); } catch (Exception ignore) {}
                try { d.delete("seen", "cid=?", new String[]{cid}); } catch (Exception ignore) {}
                try { d.delete("sync", "cid=?", new String[]{cid}); } catch (Exception ignore) {}
                return n;
            } catch (Exception e) { return 0; }
        }
    }

    /** Persist per-contact Hermes session id (durable across restarts). */
    static void setSession(String cid, String sessionId) {
        if (cid == null || cid.isEmpty() || sessionId == null || sessionId.isEmpty()) return;
        synchronized (LOCK) {
            try {
                ContentValues v = new ContentValues();
                v.put("cid", cid);
                v.put("session_id", sessionId);
                v.put("updated_ts", System.currentTimeMillis());
                db().insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception ignore) {}
        }
    }

    /** Stored per-contact Hermes session id ("" if none). */
    static String getSession(String cid) {
        if (cid == null || cid.isEmpty()) return "";
        synchronized (LOCK) {
            Cursor c = null;
            try {
                c = db().rawQuery("SELECT session_id FROM sessions WHERE cid=?", new String[]{cid});
                if (c.moveToFirst()) return c.getString(0);
            } catch (Exception ignore) {}
            finally { if (c != null) c.close(); }
        }
        return "";
    }

    static void dropSession(String cid) {
        if (cid == null || cid.isEmpty()) return;
        synchronized (LOCK) {
            try { db().delete("sessions", "cid=?", new String[]{cid}); }
            catch (Exception ignore) {}
        }
    }

    /** vc7 F1: forget the stored session id but keep history + fetched marker context.
     *  Row is deleted; setSession() recreates it. fetched flag intentionally resets —
     *  after a session is lost, server restore has nothing to pull anyway. */
    static void clearSession(String cid) { dropSession(cid); }

    /** vc7 F2: was server history already restored for this contact? */
    static boolean wasFetched(String cid) {
        if (cid == null || cid.isEmpty()) return false;
        synchronized (LOCK) {
            Cursor c = null;
            try {
                c = db().rawQuery("SELECT fetched FROM sessions WHERE cid=?", new String[]{cid});
                if (c.moveToFirst()) return c.getInt(0) != 0;
            } catch (Exception ignore) {}
            finally { if (c != null) c.close(); }
        }
        return false;
    }

    /** vc7 F2: mark server history restored (durable). */
    static void markFetched(String cid) {
        if (cid == null || cid.isEmpty()) return;
        synchronized (LOCK) {
            try { db().execSQL("UPDATE sessions SET fetched=1 WHERE cid=?", new Object[]{cid}); }
            catch (Exception ignore) {}
        }
    }

    /** vc15: wipe everything (messages + sessions + conv) and compact the file. */
    static boolean wipeAll() {
        synchronized (LOCK) {
            try {
                SQLiteDatabase d = db();
                d.delete("messages", null, null);
                d.delete("sessions", null, null);
                d.delete("conv", null, null);
                d.delete("seen", null, null);
                d.delete("sync", null, null);
                try { d.execSQL("DELETE FROM sqlite_sequence WHERE name='messages'"); } catch (Exception ignore) {}
                try { d.execSQL("VACUUM"); } catch (Exception ignore) {}
                return true;
            } catch (Exception e) { return false; }
        }
    }

    /** vc15: diagnostics for the in-app data panel. */
    static String stats(int contacts) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("contacts", contacts);
            o.put("dbVersion", SCHEMA);
            synchronized (LOCK) {
                SQLiteDatabase d = db();
                int msgs = 0, sess = 0;
                Cursor c = null;
                try {
                    c = d.rawQuery("SELECT COUNT(*) FROM messages", null);
                    if (c.moveToFirst()) msgs = c.getInt(0);
                } catch (Exception ignore) {}
                finally { if (c != null) c.close(); }
                c = null;
                try {
                    c = d.rawQuery("SELECT COUNT(*) FROM sessions", null);
                    if (c.moveToFirst()) sess = c.getInt(0);
                } catch (Exception ignore) {}
                finally { if (c != null) c.close(); }
                o.put("msgs", msgs);
                o.put("sessions", sess);
                boolean wal = false;
                try { wal = d.isWriteAheadLoggingEnabled(); } catch (Exception ignore) {}
                o.put("wal", wal);
                String path = d.getPath() == null ? "" : d.getPath();
                o.put("dbPath", path);
                long bytes = 0;
                try { bytes = new File(path).length(); } catch (Exception ignore) {}
                o.put("dbKB", bytes / 1024);
            }
        } catch (Exception e) {
            try {
                o = new JSONObject();
                o.put("ok", false);
                o.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignore) {}
        }
        return o.toString();
    }
}
