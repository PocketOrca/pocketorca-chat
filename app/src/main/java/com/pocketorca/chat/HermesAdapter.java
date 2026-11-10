package com.pocketorca.chat;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hermes api_server adapter (v1 minimal set, PLAN §3):
 *   GET  /v1/health, /v1/models
 *   GET|POST /api/sessions, GET /api/sessions/{id}/messages
 *   POST /api/sessions/{id}/chat/stream   (SSE)
 *   POST /v1/runs/{id}/stop
 * Auth: Authorization: Bearer <per-contact token> (M3: cid-scoped calls)
 *
 * Wire contract (8T api_server.py @13dc3a7, M0 static reading — "to be verified live"):
 *   SSE frame: event:<name>\n data:<json>\n\n, keepalive = ": keepalive" comment.
 *   chat/stream events: run.started, message.started, assistant.delta, assistant.commentary,
 *     tool.started, tool.completed, tool.failed, tool.progress, approval.request,
 *     assistant.completed, run.<completed|failed|cancelled>, done.
 *   Each payload stamped session_id/run_id/seq/ts.
 *   Runs: POST /v1/runs {input} -> events message.delta / message.interim / tool.* / run.* / approval.*;
 *   Runs read face (M2): GET /v1/runs/{id}, GET /v1/runs/{id}/events.
 *   terminal statuses: completed, failed, cancelled, interrupted.
 */
final class HermesAdapter {
    private HermesAdapter() {}
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final AtomicReference<String> LAST_RUN = new AtomicReference<>("");

    // ---- synchronous helpers (called from JS bridge thread; bridge is already off-UI) ----
    // M3: every call is contact-scoped — cid selects url+token from Store.

    static String health(String cid) { return get(cid, "/v1/health"); }
    /** vc27: async health — runs on the shared POOL, result pushed to JS as a
     *  health.event via the existing pushEventToJs channel (UiPush). Never
     *  blocks the JS bridge thread, so openContact() stays instant. */
    static void healthAsync(String cid) { POOL.submit(new HealthProbe(cid)); }

    /** Named Runnable (d8-safe): one background /v1/health probe.
     *  vc40: dual-address — probe both LAN and WAN (if set); either reachable = online. */
    static final class HealthProbe implements Runnable {
        private final String cid;
        HealthProbe(String cid) { this.cid = cid; }
        public void run() {
            String r = get(cid, "/v1/health");
            try {
                JSONObject o = new JSONObject(r);
                if (o.optInt("http", 0) >= 200 && o.optInt("http", 0) < 500) {
                    Store.setActiveAddr(cid, "lan");
                } else {
                    String wan = Store.wanUrl(cid);
                    if (!wan.isEmpty()) {
                        try {
                            HttpURLConnection c = openRaw(wan + "/v1/health", cid, "GET", false);
                            int code = c.getResponseCode();
                            c.disconnect();
                            if (code >= 200 && code < 500) {
                                Store.setActiveAddr(cid, "wan");
                                r = "{\"http\":" + code + ",\"body\":\"{}\",\"via\":\"wan\"}";
                            }
                        } catch (Exception ignore) {}
                    }
                }
            } catch (Exception ignore) {}
            MainActivity.pushEventToJs("{\"event\":\"health\",\"payload\":{\"cid\":\""
                    + cid.replace("\"", "").replace("\\", "") + "\",\"result\":" + r + "}}");
        }
    }
    static String models(String cid) { return get(cid, "/v1/models"); }
    static String listSessions(String cid) { return get(cid, "/api/sessions"); }
    static String getMessages(String cid, String id) { return getMessages(cid, id, 0); }
    static String getMessages(String cid, String id, int offset) {
        String path = "/api/sessions/" + id + "/messages?offset=" + offset + "&limit=500";
        return get(cid, path);
    }

    static String createSession(String cid, String title) {
        try {
            JSONObject body = new JSONObject();
            if (title != null && !title.trim().isEmpty()) body.put("source", "api_server");
            String resp = post(cid, "/api/sessions", body.toString());
            try {
                JSONObject o = new JSONObject(resp);
                String id = o.optString("session_id", o.optString("id", ""));
                if (!id.isEmpty()) LAST_RUN.set("");
            } catch (Exception ignore) {}
            return resp;
        } catch (Exception e) { return err(e); }
    }

    /** Blocking chat/stream; returns {status, events:[...]} JSON to JS. */
    static String chatStreamSync(String cid, String sessionId, String message) {
        StreamCollector sink = new StreamCollector(cid);
        try {
            JSONObject body = new JSONObject();
            body.put("message", message == null ? "" : message);
            streamSse(cid, "/api/sessions/" + sessionId + "/chat/stream", body.toString(), sink);
            return "{\"status\":\"" + (sink.status.isEmpty() ? "error" : sink.status)
                    + "\",\"events\":[" + sink.events.toString() + "]}";
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error\":\"" + esc(e.getMessage()) + "\",\"events\":[]}";
        }
    }

    /** Named SseSink (d8-safe): slims events, tracks terminal status, forwards approvals.
     *  vc40: carries origin cid so JS routes events to the RIGHT contact. */
    static final class StreamCollector implements SseSink {
        final StringBuilder events = new StringBuilder();
        boolean first = true;
        String status = "";
        final String cid;
        StreamCollector() { this.cid = ""; }
        StreamCollector(String cid) { this.cid = cid == null ? "" : cid; }

        public void event(String name, JSONObject payload) {
            if ("done".equals(name)) return;
            try {
                if (!first) events.append(",");
                first = false;
                JSONObject slim = new JSONObject();
                slim.put("event", name);
                slim.put("payload", payload);
                events.append(slim.toString());
                if (name.startsWith("run.") && !"run.started".equals(name)) status = name.substring(4);
                if ("error".equals(name) && !payload.has("message") && !payload.has("error")
                        && payload.has("http")) {
                    try { payload.put("message", "http " + payload.getInt("http")); } catch (Exception ignore) {}
                }
                // Forward every event to the live JS handler (demo previously only pushed approvals)
                // vc40: cid stamped so JS routes by ORIGIN contact, not by what's on screen
                payload.put("cid", cid);
                MainActivity.pushEventToJs("{\"event\":\"" + name + "\",\"payload\":" + payload.toString() + "}");
            } catch (Exception ignore) {}
        }
    }

    /** Async chat/stream: pushes each event to JS via window.__sseEvent, returns nothing. */
    static void chatStreamAsync(final String cid, final String sessionId, final String message) {
        POOL.submit(new AsyncTurn(cid, sessionId, message));
    }

    /** Named Runnable (d8-safe): one async chat turn. */
    static final class AsyncTurn implements Runnable {
        private final String cid, sessionId, message;
        AsyncTurn(String cid, String sessionId, String message) { this.cid = cid; this.sessionId = sessionId; this.message = message; }
        public void run() {
            String result = chatStreamSync(cid, sessionId, message);
            // vc41: stamp the origin cid — JS guards a stale chat.done from a
            // concurrent turn against clearing the on-screen turn's state.
            try {
                JSONObject r = new JSONObject(result);
                r.put("cid", cid);
                result = r.toString();
            } catch (Exception ignore) {}
            MainActivity.pushEventToJs("{\"event\":\"chat.done\",\"payload\":" + result + "}");
        }
    }

    // ---- vc11: delegation relay (PLAN §4, client-side scheduler) ----
    // Red line: the relay NEVER auto-feeds B's reply back into any agent. It only
    // lands in the originating contact's UI + local DB (JS side). One HTTP turn,
    // zero loop logic on the Java side.
    // Events are mirrored onto a dedicated relay.* channel so they never touch the
    // main chat turn state (botEl / runId / busy are JS-side main-turn only).

    /** relayId -> {toCid, runId} so JS can cancel a relay mid-flight. Single-threaded per relay. */
    private static final java.util.concurrent.ConcurrentHashMap<String, JSONObject> RELAYS =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void relayStart(String fromCid, String toCid, String relayId, String message) {
        POOL.submit(new RelayTurn(fromCid, toCid, relayId, message));
    }

    static String relayStop(String relayId) {
        JSONObject st = RELAYS.get(relayId);
        if (st == null) return "{\"http\":404,\"error\":\"no such relay\"}";
        try {
            return post(st.optString("toCid"), "/v1/runs/" + st.optString("runId") + "/stop", "{}");
        } catch (Exception e) { return err(e); }
    }

    /** Named Runnable (d8-safe): one relayed turn on the TARGET (B) endpoint. */
    static final class RelayTurn implements Runnable {
        private final String fromCid, toCid, relayId, message;
        RelayTurn(String fromCid, String toCid, String relayId, String message) {
            this.fromCid = fromCid; this.toCid = toCid; this.relayId = relayId; this.message = message;
        }
        public void run() {
            try {
                // B-side session: reuse stored id, else create one (server = agent memory layer).
                String sid = ChatDB.getSession(toCid);
                if (sid == null || sid.isEmpty()) {
                    String resp = createSession(toCid, "");
                    String created = "";
                    try {
                        JSONObject o = new JSONObject(resp);
                        String body = o.optString("body", "");
                        if (o.optInt("http", 0) == 200 && !body.isEmpty() && !"null".equals(body)) {
                            JSONObject b = new JSONObject(body);
                            created = b.optString("session_id", "");
                            if (created.isEmpty()) created = b.optString("id", "");
                            if (created.isEmpty() && b.optJSONObject("session") != null)
                                created = b.optJSONObject("session").optString("id", "");
                        }
                    } catch (Exception ignore) {}
                    if (created.isEmpty()) {
                        pushRelay(relayId, "relay.error", new JSONObject().put("message", "B 建会话失败: " + resp));
                        return;
                    }
                    sid = created;
                    ChatDB.setSession(toCid, sid);
                }
                JSONObject st = new JSONObject().put("toCid", toCid);
                RELAYS.put(relayId, st);
                JSONObject body = new JSONObject().put("message", message == null ? "" : message);
                RelaySink sink = new RelaySink(relayId, toCid);
                streamSse(toCid, "/api/sessions/" + sid + "/chat/stream", body.toString(), sink);
                RELAYS.remove(relayId);
                if (!sink.terminal)   // stream ended without a terminal event (server cut, keepalive timeout)
                    pushRelay(relayId, "relay.done", new JSONObject()
                            .put("content", sink.content.toString())
                            .put("status", sink.status.isEmpty() ? "ended" : sink.status));
            } catch (Exception e) {
                RELAYS.remove(relayId);
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                try { pushRelay(relayId, "relay.error", new JSONObject().put("message", msg)); } catch (Exception ignore) {}
            }
        }
    }

    /** Named SseSink (d8-safe): mirrors B-turn events onto the relay.* channel. */
    static final class RelaySink implements SseSink {
        final String relayId, toCid;
        final StringBuilder content = new StringBuilder();
        String status = "";
        boolean terminal;
        RelaySink(String relayId, String toCid) { this.relayId = relayId; this.toCid = toCid; }
        public void event(String name, JSONObject payload) {
            try {
                if ("done".equals(name)) return;   // terminal relay.done comes from RelayTurn
                if ("run.started".equals(name)) {
                    String runId = payload.optString("run_id", "");
                    JSONObject st = RELAYS.get(relayId);
                    if (st != null && !runId.isEmpty()) st.put("runId", runId);
                    pushRelay(relayId, "relay.status", new JSONObject().put("phase", "running").put("runId", runId));
                } else if ("assistant.delta".equals(name)) {
                    pushRelay(relayId, "relay.delta", new JSONObject().put("delta", payload.optString("delta", "")));
                } else if ("assistant.completed".equals(name)) {
                    content.setLength(0);
                    content.append(payload.optString("content", ""));
                    pushRelay(relayId, "relay.status", new JSONObject().put("phase", "completed"));
                } else if (name.startsWith("tool.")) {
                    pushRelay(relayId, "relay.status", new JSONObject().put("phase", "tool")
                            .put("text", name + " " + payload.optString("tool_name", payload.optString("tool", ""))));
                } else if ("approval.request".equals(name)) {
                    JSONObject p = new JSONObject(payload.toString());
                    p.put("toCid", toCid);   // JS approval card must approve on the TARGET endpoint
                    pushRelay(relayId, "relay.approval", p);
                } else if ("error".equals(name)) {
                    String msg = payload.optString("message", "");
                    if (msg.isEmpty()) msg = payload.optString("error", "http " + payload.optInt("http", 0));
                    pushRelay(relayId, "relay.error", new JSONObject().put("message", msg));
                    terminal = true;
                } else if (name.startsWith("run.")) {
                    status = name.substring(4);
                    terminal = true;
                    // every terminal run.* emits relay.done itself; RelayTurn only
                    // backstops the stream-cut case (terminal never set)
                    pushRelay(relayId, "relay.done", new JSONObject()
                            .put("content", content.toString()).put("status", status));
                }
            } catch (Exception ignore) {}
        }
    }

    private static void pushRelay(String relayId, String name, JSONObject payload) {
        try {
            payload.put("relayId", relayId);
            MainActivity.pushEventToJs("{\"event\":\"" + name + "\",\"payload\":" + payload.toString() + "}");
        } catch (Exception ignore) {}
    }

    static String stopRun(String cid, String runId) {
        try {
            return post(cid, "/v1/runs/" + runId + "/stop", "{}");
        } catch (Exception e) { return err(e); }
    }

    /** vc22: lock the model for one session (POST /api/sessions/{id}/model).
     *  vc35: "provider/model" slash form put the WHOLE string into model with an
     *  empty provider — server falls back to the default provider and 400s with
     *  "模型不存在". Split into separate provider/model fields here. */
    static JSONObject splitModel(String model) throws Exception {
        JSONObject body = new JSONObject();
        String m = model == null ? "" : model.trim();
        int i = m.indexOf('/');
        if (i > 0 && !m.startsWith("/") && m.indexOf('/', i + 1) < 0) {
            body.put("provider", m.substring(0, i));
            body.put("model", m.substring(i + 1));
        } else {
            body.put("model", m);
        }
        return body;
    }

    static String setSessionModel(String cid, String sid, String model) {
        try {
            return post(cid, "/api/sessions/" + sid + "/model", splitModel(model).toString());
        } catch (Exception e) { return err(e); }
    }

    static String createSessionLocked(String cid, String model) {
        try {
            JSONObject body = splitModel(model);
            body.put("require_model_lock", true);
            return post(cid, "/api/sessions", body.toString());
        } catch (Exception e) { return err(e); }
    }

    /** vc33: model catalog for the picker (GET /api/model/options). */
    static String modelOptions(String cid) {
        return get(cid, "/api/model/options");
    }

    /** vc33: read back one session's row (model field included) — /model 显示当前模型用. */
    static String sessionInfo(String cid, String sid) {
        return get(cid, "/api/sessions/" + sid);
    }

    static String steerRun(String cid, String runId, String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("input", text == null ? "" : text);
            return post(cid, "/v1/runs/" + runId + "/steer", body.toString());
        } catch (Exception e) { return err(e); }
    }

    static String approveRun(String cid, String runId, String choice, String requestId) {
        try {
            JSONObject body = new JSONObject();
            body.put("choice", choice == null ? "once" : choice);
            if (requestId != null && !requestId.isEmpty()) body.put("request_id", requestId);
            return post(cid, "/v1/runs/" + runId + "/approval", body.toString());
        } catch (Exception e) { return err(e); }
    }

    static String runStatus(String cid, String runId) {
        try { return get(cid, "/v1/runs/" + runId); } catch (Exception e) { return err(e); }
    }

    static String runEvents(String cid, String runId) {
        try { return get(cid, "/v1/runs/" + runId + "/events"); } catch (Exception e) { return err(e); }
    }

    // ---- B1a: POST /v1/runs (primary send path) + SSE event subscription ----

    /**
     * Send message via POST /v1/runs with explicit session_id and Idempotency-Key header.
     * Per BG-TASK §4.3: Idempotency-Key MUST be in HTTP header (body placement is无效 per B0).
     * Returns {http, body} JSON.
     */
    static String sendRun(String cid, String sessionId, String message) {
        try {
            String idempKey = "poc-" + System.currentTimeMillis() + "-" + Long.toHexString(System.nanoTime());
            JSONObject body = new JSONObject();
            body.put("session_id", sessionId);
            body.put("input", message == null ? "" : message);
            String resp = postWithHeaders(cid, "/v1/runs", body.toString(),
                    new String[]{"Idempotency-Key", "Content-Type"},
                    new String[]{idempKey, "application/json"});
            try {
                JSONObject o = new JSONObject(resp);
                String bodyStr = o.optString("body", "");
                if (o.optInt("http", 0) == 200 && !bodyStr.isEmpty() && !"null".equals(bodyStr)) {
                    JSONObject b = new JSONObject(bodyStr);
                    String runId = b.optString("run_id", "");
                    if (!runId.isEmpty()) LAST_RUN.set(runId);
                }
            } catch (Exception ignore) {}
            return resp;
        } catch (Exception e) { return err(e); }
    }

    /**
     * Subscribe to SSE events for a running run: GET /v1/runs/{id}/events.
     * Streams events to the provided SseSink; when a terminal event is received,
     * the subscription is automatically cancelled.
     * This is the async path — returns nothing; events are pushed via sink.event().
     */
    static void subscribeRunEvents(final String cid, final String runId, final SseSink sink) {
        POOL.submit(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection c = conn(cid, "/v1/runs/" + runId + "/events", "GET", false);
                    // Idempotency-Key not needed for GET, but Accept header for SSE
                    c.setRequestProperty("Accept", "text/event-stream");
                    int code = c.getResponseCode();
                    if (code != 200) {
                        JSONObject p = new JSONObject();
                        p.put("http", code);
                        p.put("error", readError(c));
                        sink.event("error", p);
                        sink.event("done", new JSONObject());
                        return;
                    }
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    String line, eventName = null;
                    StringBuilder data = new StringBuilder();
                    while ((line = r.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (data.length() > 0 || eventName != null) {
                                String payloadStr = data.toString();
                                String name = eventName == null ? "message" : eventName;
                                try {
                                    sink.event(name, new JSONObject(payloadStr));
                                } catch (Exception parse) {
                                    try {
                                        JSONObject p = new JSONObject();
                                        p.put("raw", payloadStr);
                                        sink.event(name, p);
                                    } catch (Exception ignore) {}
                                }
                            }
                            eventName = null; data.setLength(0);
                        } else if (line.startsWith("event:")) {
                            eventName = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (data.length() > 0) data.append("\n");
                            data.append(line.substring(5).trim());
                        }
                    }
                    r.close();
                    sink.event("done", new JSONObject());
                } catch (Exception e) {
                    try {
                        JSONObject p = new JSONObject();
                        p.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                        sink.event("error", p);
                    } catch (Exception ignore) {}
                    sink.event("done", new JSONObject());
                }
            }
        });
    }

    // ---- HTTP core ----

    private interface SseSink { void event(String name, JSONObject payload); }

    /** vc40: periodic LAN re-probe (60s min gap) — if LAN works again, switch back. */
    private static void maybeReprobeLan(final String cid) {
        long now = System.currentTimeMillis();
        if (now - Store.lastProbe(cid) < 60000L) return;
        Store.setLastProbe(cid, now);
        POOL.submit(new Runnable() { public void run() {
            try {
                HttpURLConnection c = openRaw(Store.url(cid) + "/v1/health", cid, "GET", false);
                int code = c.getResponseCode();
                c.disconnect();
                if (code >= 200 && code < 500) Store.setActiveAddr(cid, "lan");   // reachable
            } catch (Exception ignore) {}
        }});
    }

    /** vc40: raw connection to an explicit base URL (failover probe / normal open). */
    private static HttpURLConnection openRaw(String base, String cid, String method, boolean doOut) throws Exception {
        URL u = new URL(base);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(doOut ? 0 : 10000);
        String tok = Store.token(cid);
        if (!tok.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + tok);
        c.setRequestProperty("Accept", "text/event-stream, application/json");
        if (doOut) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); }
        return c;
    }

    private static HttpURLConnection conn(String cid, String path, String method, boolean doOut) throws Exception {
        String lan = Store.url(cid);
        if (lan == null || lan.isEmpty()) throw new IllegalStateException("联系人不存在或已删除");
        String wan = Store.wanUrl(cid);
        String active = Store.activeAddr(cid);
        // Build candidate list: active first, then the other address if configured.
        java.util.ArrayList<String> bases = new java.util.ArrayList<>();
        String first = "wan".equals(active) && !wan.isEmpty() ? wan : lan;
        bases.add(first);
        String other = first.equals(lan) ? wan : lan;
        if (!other.isEmpty() && !other.equals(first)) bases.add(other);

        Exception last = null;
        for (int i = 0; i < bases.size(); i++) {
            String base = bases.get(i);
            try {
                HttpURLConnection c = openRaw(base + path, cid, method, doOut);
                // Failover trigger: for plain requests, force the response code here
                // so a connect failure throws before we hand the connection back.
                if (!doOut || method.equals("GET")) c.getResponseCode();
                if (i > 0) Store.setActiveAddr(cid, base.equals(wan) ? "wan" : "lan");   // remember winner
                // not on LAN → schedule periodic LAN re-probe (come-home self-heal)
                if (!base.equals(lan)) maybeReprobeLan(cid);
                return c;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("无可用地址");
    }

    private static String get(String cid, String path) {
        try {
            HttpURLConnection c = conn(cid, path, "GET", false);
            int code = c.getResponseCode();
            String body = readAll(c);
            return "{\"http\":" + code + ",\"body\":" + jq(body) + "}";
        } catch (Exception e) { return err(e); }
    }

    private static String post(String cid, String path, String json) {
        return postWithHeaders(cid, path, json, null, null);
    }

    /** POST with extra headers (for Idempotency-Key). vc40: connect-level failover retry. */
    private static String postWithHeaders(String cid, String path, String json,
                                          String[] headerNames, String[] headerValues) {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpURLConnection c = conn(cid, path, "POST", true);
                if (headerNames != null && headerValues != null) {
                    for (int i = 0; i < headerNames.length; i++) {
                        c.setRequestProperty(headerNames[i], headerValues[i]);
                    }
                }
                OutputStream out = c.getOutputStream();
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.close();
                int code = c.getResponseCode();
                String body = readAll(c);
                return "{\"http\":" + code + ",\"body\":" + jq(body) + "}";
            } catch (Exception e) {
                last = e;
                // first failure: conn() already tried the other address; retry once
                // more so a flipped activeAddr takes effect on this same call
            }
        }
        return err(last);
    }

    private static void streamSse(String cid, String path, String json, SseSink sink) throws Exception {
        // vc40: two attempts so a connect-level failover (conn() flips activeAddr)
        // takes effect within the same send.
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try { streamSseOnce(cid, path, json, sink); return; }
            catch (Exception e) { last = e; }
        }
        throw last;
    }

    private static void streamSseOnce(String cid, String path, String json, SseSink sink) throws Exception {
        HttpURLConnection c = conn(cid, path, "POST", true);
        OutputStream out = c.getOutputStream();
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.close();
        int code = c.getResponseCode();
        if (code != 200) {
            JSONObject p = new JSONObject();
            p.put("http", code);
            p.put("error", readError(c));
            sink.event("error", p);
            sink.event("done", new JSONObject());
            return;
        }
        BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String line, eventName = null;
        StringBuilder data = new StringBuilder();
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0 || eventName != null) {
                    String payloadStr = data.toString();
                    String name = eventName == null ? "message" : eventName;
                    try {
                        sink.event(name, new JSONObject(payloadStr));
                    } catch (Exception parse) {
                        try {
                            JSONObject p = new JSONObject();
                            p.put("raw", payloadStr);
                            sink.event(name, p);
                        } catch (Exception ignore) {}
                    }
                }
                eventName = null; data.setLength(0);
            } else if (line.startsWith("event:")) {
                eventName = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append("\n");
                data.append(line.substring(5).trim());
            } // ":" keepalive comments and id:/retry: are ignored
        }
        r.close();
        sink.event("done", new JSONObject());
    }

    // ---- utils ----

    private static String readAll(HttpURLConnection c) throws Exception {
        java.io.InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.toString();
    }

    private static String readError(HttpURLConnection c) throws Exception {
        java.io.InputStream in = c.getErrorStream();
        if (in == null) return "http " + c.getResponseCode();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.length() > 400 ? sb.substring(0, 400) : sb.toString();
    }

    private static String jq(String s) {
        if (s == null || s.isEmpty()) return "null";
        try { new JSONObject(s); return s; } catch (Exception e) { /* not json */ }
        return "\"" + esc(s) + "\"";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    private static String err(Exception e) {
        return "{\"http\":0,\"error\":\"" + esc(e.getMessage() == null ? e.toString() : e.getMessage()) + "\"}";
    }
}
