package com.pocketorca.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.lang.ref.WeakReference;

/**
 * PocketOrca-Chat demo (D0/M1): WebView shell over assets/index.html.
 * Java side = HermesAdapter (sessions + chat/stream SSE + runs control),
 * JS side = contact list / chat UI. Same split as PocketOrca-LLM (proven chain).
 *
 * d8-safe rules (vc62 lesson): static nested classes only, no anonymous
 * classes, no enums, no lambdas targeting old desugar paths.
 */
public class MainActivity extends Activity {
    private WebView web;
    private boolean boundPoll = false;
    private PollConnection pollConn;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        // vc15: without a WebChromeClient, window.confirm() silently returns false
        // (root cause of "点删除没反应"). JS no longer relies on it, but keep the
        // native dialogs working so any future confirm()/alert() isn't a dead end.
        web.setWebChromeClient(new Chrome(this));
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");

        // B1a: Start and bind PollScheduler
        Intent pollIntent = new Intent(this, PollScheduler.class);
        startForegroundService(pollIntent);
        pollConn = new PollConnection(this);
        bindService(pollIntent, pollConn, BIND_AUTO_CREATE);

        // Runtime POST_NOTIFICATIONS permission request (API 33+)
        requestNotificationsPermission();
    }

    /** Request POST_NOTIFICATIONS at runtime (Android 13+). */
    @SuppressWarnings("deprecation")
    private void requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.__onBack && window.__onBack();", null);
        else super.onBackPressed();
    }

    /** JS bridge: every method marshals to HermesAdapter worker threads.
     *  d8-safe: static nested class (non-static inner classes make javac emit
     *  MethodParameters with null names, which crashes d8 8.2.2-dev with NPE). */
    static final class Bridge {
        // M3 contacts CRUD (Store.contacts.json)
        @JavascriptInterface public String listContacts() { return Store.listContacts(); }
        @JavascriptInterface public String addContact(String json) { return Store.addContact(json); }
        @JavascriptInterface public String updateContact(String id, String json) { return Store.updateContact(id, json); }
        @JavascriptInterface public String removeContact(String id) { return Store.removeContact(id); }
        // vc6: local chat history (WeChat-style, truth source = app DB)
        @JavascriptInterface public String chatRecent(String cid, String nStr) {
            int n = 200; try { n = Integer.parseInt(nStr); } catch (Exception ignore) {}
            return ChatDB.recent(cid, n);
        }
        // vc10: JS-side transcript writes go through the bridge (was: JS called a
        // nonexistent ChatDB global — every insert threw ReferenceError, swallowed
        // silently, so nothing ever landed in the local DB).
        @JavascriptInterface public void chatInsert(String cid, String role, String text) {
            if (cid != null && !cid.isEmpty()) ChatDB.insert(cid, role, text);
        }
        // vc21: save a base64 data-URL image (from a bot MEDIA: bubble) into
        // MediaStore Pictures/PocketOrca — API 29+ needs no runtime permission.
        // Runs on the bridge thread; heavy decode/write kept here, JS just toasts.
        @JavascriptInterface public String saveImageDataUrl(String dataUrl) {
            return MediaSaver.saveFromDataUrl(this, dataUrl);
        }
        @JavascriptInterface public void chatClear(String cid) { ChatDB.clear(cid); }
        // vc15: DB v3 — count / paging / stats / full local wipe
        @JavascriptInterface public String chatCount(String cid) { return String.valueOf(ChatDB.count(cid)); }
        @JavascriptInterface public String chatPage(String cid, String beforeSeq, String limit) {
            long before = 0; int lim = 50;
            try { before = Long.parseLong(beforeSeq); } catch (Exception ignore) {}
            try { lim = Integer.parseInt(limit); } catch (Exception ignore) {}
            return ChatDB.page(cid, before, lim);
        }
        @JavascriptInterface public String dataStats() { return ChatDB.stats(Store.count()); }
        // vc22: history search + jump-to-message window
        @JavascriptInterface public String chatSearch(String q, String cid, String limit) {
            int lim = 200;
            try { lim = Integer.parseInt(limit); } catch (Exception ignore) {}
            return ChatDB.search(q, cid, lim);
        }
        @JavascriptInterface public String chatWindow(String cid, String seq, String before, String after) {
            long anchor = 0; int beforeN = 50, afterN = 50;
            try { anchor = Long.parseLong(seq); } catch (Exception ignore) {}
            try { beforeN = Integer.parseInt(before); } catch (Exception ignore) {}
            try { afterN = Integer.parseInt(after); } catch (Exception ignore) {}
            return ChatDB.window(cid, anchor, beforeN, afterN);
        }
        // vc22: slash command /model — lock the model for the current session
        @JavascriptInterface public String setSessionModel(String cid, String sid, String model) {
            return HermesAdapter.setSessionModel(cid, sid, model);
        }
        // vc33: create a session WITH its model lock in one call; + catalog for the picker
        @JavascriptInterface public String createSessionLocked(String cid, String model) {
            return HermesAdapter.createSessionLocked(cid, model);
        }
        @JavascriptInterface public String modelOptions(String cid) {
            return HermesAdapter.modelOptions(cid);
        }
        @JavascriptInterface public String sessionInfo(String cid, String sid) {
            return HermesAdapter.sessionInfo(cid, sid);
        }
        @JavascriptInterface public String wipeAll() {
            boolean a = Store.wipeAll();
            boolean b = ChatDB.wipeAll();
            return a && b ? "{\"ok\":true}" : "{\"ok\":false,\"error\":\"wipe failed\"}";
        }
        @JavascriptInterface public String chatSessionGet(String cid) { return ChatDB.getSession(cid); }
        @JavascriptInterface public void chatSessionPut(String cid, String sid) { ChatDB.setSession(cid, sid); }
        // vc7: session self-heal + server-history restore
        @JavascriptInterface public void chatSessionClear(String cid) { ChatDB.clearSession(cid); }
        @JavascriptInterface public String chatSessionWasFetched(String cid) { return ChatDB.wasFetched(cid) ? "1" : "0"; }
        @JavascriptInterface public void chatSessionMarkFetched(String cid) { ChatDB.markFetched(cid); }
        @JavascriptInterface public String listSessions(String cid) { return HermesAdapter.listSessions(cid); }
        @JavascriptInterface public String createSession(String cid, String title) { return HermesAdapter.createSession(cid, title); }
        @JavascriptInterface public String getMessages(String cid, String sessionId) { return HermesAdapter.getMessages(cid, sessionId); }
        /** Synchronous stream: returns one JSON {events:[...], status} when the turn ends. */
        @JavascriptInterface public String chatStream(String cid, String sessionId, String message) {
            return HermesAdapter.chatStreamSync(cid, sessionId, message);
        }
        /** Incremental mode: events are pushed to JS via __sseEvent(). */
        @JavascriptInterface public void chatStreamAsync(String cid, String sessionId, String message) {
            HermesAdapter.chatStreamAsync(cid, sessionId, message);
        }
        @JavascriptInterface public String stopRun(String cid, String runId) { return HermesAdapter.stopRun(cid, runId); }
        @JavascriptInterface public String steerRun(String cid, String runId, String text) { return HermesAdapter.steerRun(cid, runId, text); }
        @JavascriptInterface public String approveRun(String cid, String runId, String choice, String requestId) { return HermesAdapter.approveRun(cid, runId, choice, requestId); }
        @JavascriptInterface public String runStatus(String cid, String runId) { return HermesAdapter.runStatus(cid, runId); }
        @JavascriptInterface public String runEvents(String cid, String runId) { return HermesAdapter.runEvents(cid, runId); }
        // B1a: primary send path via POST /v1/runs
        @JavascriptInterface public String sendRun(String cid, String sessionId, String message) {
            return HermesAdapter.sendRun(cid, sessionId, message);
        }
        // B1a: subscribe to SSE events for a running run
        @JavascriptInterface public void subscribeRunEvents(String cid, String runId) {
            HermesAdapter.subscribeRunEvents(cid, runId, new HermesAdapter.StreamCollector());
        }
        // vc11: delegation relay (PLAN §4) — client-side scheduler, one turn on the
        // TARGET endpoint, reply lands back in the originating chat via relay.* events.
        // Red line: no loop — B's reply is never auto-fed to any agent.
        @JavascriptInterface public void relayStart(String fromCid, String toCid, String relayId, String message) {
            if (toCid == null || toCid.isEmpty() || relayId == null || relayId.isEmpty()) return;
            HermesAdapter.relayStart(fromCid, toCid, relayId, message);
        }
        @JavascriptInterface public String relayStop(String relayId) { return HermesAdapter.relayStop(relayId); }
        @JavascriptInterface public void copyText(String text) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        Store.context().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", text == null ? "" : text));
            } catch (Exception ignore) {}
        }
        @JavascriptInterface public void healthAsync(String cid) { HermesAdapter.healthAsync(cid); }
        @JavascriptInterface public String health(String cid) { return HermesAdapter.health(cid); }
        @JavascriptInterface public String models(String cid) { return HermesAdapter.models(cid); }
        @JavascriptInterface public void jsCallback(final String js) {
            MainActivity m = instance;
            if (m != null) m.web.post(new JsEval(js));
        }
        // vc26: run activity → foreground notification text ("" = back to 保持连接)
        @JavascriptInterface public void setRunActivity(String text) {
            PollScheduler s = pollSvc;
            if (s != null) s.updateRunActivity(text == null ? "" : text);
        }
        // vc26: version string for the settings sheet

        // vc29: user-configurable poll interval (ms, clamped 30s-10min in PollScheduler)
        @JavascriptInterface public void setPollInterval(String msStr) {
            try {
                long ms = Long.parseLong(msStr.trim());
                PollScheduler s = pollSvc;
                if (s != null) s.setUserInterval(ms);
            } catch (Exception ignore) {}
        }
        @JavascriptInterface public String getPollInterval() {
            PollScheduler s = pollSvc;
            return String.valueOf(s != null ? s.getUserInterval() : 60000L);
        }

        @JavascriptInterface public String appVersion() {
            try {
                return Store.context().getPackageManager()
                        .getPackageInfo(Store.context().getPackageName(), 0).versionName;
            } catch (Exception e) { return ""; }
        }
    }

    /** Named static Runnable (d8-safe: no anonymous classes). */
    static final class JsEval implements Runnable {
        private final String js;
        JsEval(String js) { this.js = js; }
        public void run() {
            MainActivity m = instance;
            if (m != null && m.web != null) m.web.evaluateJavascript(js, null);
        }
    }

    static void pushEventToJs(final String eventJson) {
        final MainActivity a = instance;
        if (a == null) return;
        a.runOnUiThread(new UiPush(eventJson));
    }

    /** Named static Runnable (d8-safe): evaluate __sseEvent on the UI thread. */
    static final class UiPush implements Runnable {
        private final String json;
        UiPush(String json) { this.json = json; }
        public void run() {
            MainActivity m = instance;
            if (m == null || m.web == null) return;
            String safe = json.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "");
            m.web.evaluateJavascript(
                "window.__sseEvent && window.__sseEvent('" + safe + "');", null);
        }
    }

    /** vc15: native dialog support (d8-safe: named static nested classes only). */
    static final class Chrome extends WebChromeClient {
        private final WeakReference<MainActivity> host;
        Chrome(MainActivity a) { host = new WeakReference<MainActivity>(a); }

        @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            MainActivity a = host.get();
            if (a == null) { result.cancel(); return true; }
            new AlertDialog.Builder(a).setMessage(message)
                    .setPositiveButton("确定", new Answer(result, true))
                    .setNegativeButton("取消", new Answer(result, false))
                    .show();
            return true;
        }

        @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            MainActivity a = host.get();
            if (a == null) { result.cancel(); return true; }
            new AlertDialog.Builder(a).setMessage(message)
                    .setPositiveButton("好", new Answer(result, true))
                    .show();
            return true;
        }
    }

    static final class Answer implements DialogInterface.OnClickListener {
        private final JsResult result;
        private final boolean ok;
        Answer(JsResult r, boolean ok) { this.result = r; this.ok = ok; }
        @Override public void onClick(DialogInterface d, int which) {
            if (ok) result.confirm(); else result.cancel();
        }
    }

    /** B1a: ServiceConnection for PollScheduler (static nested = d8-safe). */
    static final class PollConnection implements ServiceConnection {
        private volatile MainActivity activity;
        PollConnection(MainActivity a) { activity = a; }
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            activity.boundPoll = true;
            // vc26: keep a ref so the JS bridge can update the foreground notification
            // ("正在处理…"/"正在使用工具 X") from the run activity events.
            if (service instanceof PollScheduler.LocalBinder) {
                pollSvc = ((PollScheduler.LocalBinder) service).getService();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            activity.boundPoll = false;
            pollSvc = null;
        }
    }

    /** vc26: live PollScheduler ref (set on bind, cleared on disconnect). */
    private static volatile PollScheduler pollSvc = null;

    private static MainActivity instance;
    @Override protected void onResume() { super.onResume(); instance = this; }
    @Override protected void onPause() { super.onPause(); }
    @Override protected void onDestroy() {
        if (pollConn != null) {
            unbindService(pollConn);
            pollConn = null;
        }
        super.onDestroy();
        instance = null;
    }
}
