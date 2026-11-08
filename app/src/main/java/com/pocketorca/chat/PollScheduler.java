package com.pocketorca.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * B1a: ForegroundService + PollScheduler.
 *
 * Responsibilities:
 * 1. Foreground service with persistent "保持连接" notification (START_STICKY).
 * 2. Screen-on poll every 30-60s; screen-off poll every 4-5min (fixed).
 * 3. On network change / screen-on: immediate poll (don't wait for cycle).
 * 4. Poll GET /api/sessions (limit≤200) → compare last_active → only pull
 *    changed sessions' messages (GET /api/sessions/{id}/messages, offset incremental).
 * 5. Push received messages to JS via MainActivity.pushEventToJs.
 * 6. Doze/OneUI battery-optimisation whitelist guide (no vendor hacks).
 * 7. Offline approval timeout: 120s → local "expired" notification.
 *
 * d8-safe: all static nested classes, no lambdas, no anonymous classes.
 */
public class PollScheduler extends Service {

    private static final String TAG = "PollScheduler";
    private static final String FG_CHANNEL_ID = "pocketorca_fg";
    private static final String FG_CHANNEL_NAME = "后台连接";
    private static final String NOTIF_CHANNEL_ID = "pocketorca_msgs";
    // vc18: heads-up 横幅由渠道 importance 决定（旧渠道 DEFAULT 不弹横幅），
    // 渠道 importance 创建后不可变 → 换新 ID 让系统重建渠道，旧的删掉
    private static final String NOTIF_CHANNEL_ID_V2 = "pocketorca_msgs_v2";
    private static final String NOTIF_CHANNEL_NAME = "新消息";
    private static final String APPROVAL_CHANNEL_ID = "pocketorca_approval";
    private static final String APPROVAL_CHANNEL_NAME = "审批通知";

    // vc29: 可调轮询间隔（设置面板四档，prefs 持久化，默认 1min）
    // 亮屏/熄屏同一档：熄屏用 setExactAndAllowWhileIdle 精确闹钟驱动穿透 Doze，
    // 不再依赖 Handler/scheduler（Doze 会把 240s 档实测推迟到 9min，1107 agent.log 实据）
    static final long[] POLL_CHOICES_MS = {30_000L, 60_000L, 180_000L, 300_000L};
    static final long POLL_DEFAULT_MS = 60_000L;
    static final String PREFS_NAME = "pollprefs";
    static final String PREF_INTERVAL = "interval_ms";
    private static final long MIN_INTERVAL_MS = 30_000L;   // setExactAndAllowWhileIdle 配额远够，不担心
    private static final long MAX_INTERVAL_MS = 600_000L;  // 防脏值

    /** 用户设置的轮询间隔（亮熄屏同档）。 */
    private volatile long userIntervalMs = POLL_DEFAULT_MS;

    // Session state tracking
    private Map<String, Long> sessionLastActive = new HashMap<>();
    private Map<String, String> sessionNameCache = new HashMap<>();

    // Scheduling
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PollScheduler");
        t.setDaemon(true);
        return t;
    });
    private android.app.AlarmManager alarmMgr = null;
    private PendingIntent alarmPi = null;
    /** vc29: 防止 AlarmTask 与立即轮询并发打爆（上一轮还没跑完就跳过本轮） */
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);

    // State
    private final AtomicBoolean isScreenOn = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Binder for MainActivity
    private final IBinder binder = new LocalBinder(this);

    // Offline approval tracking: contactId -> lastSeenTimestamp
    private Map<String, Long> approvalRequests = new HashMap<>();
    private static final long APPROVAL_TIMEOUT_MS = 120_000L; // 120s per BG-TASK §1.6

    // Notification aggregation for idle
    private int pendingMessageCount = 0;
    // vc18: 通知带联系人名+最后一条消息预览（微信体验）
    private final Map<String, String> pendingPreview = new HashMap<>();

    /** Screen state receiver (named static nested — bt34 d8 匿名类 NPE 铁律) */
    private final BroadcastReceiver screenReceiver = new ScreenReceiver(this);

    /** vc29: exact-alarm tick receiver (dynamic, no manifest entry needed) */
    private final BroadcastReceiver alarmReceiver = new AlarmReceiver(this);

    /** Network change receiver (named static nested) */
    private final BroadcastReceiver networkReceiver = new NetworkReceiver(this);

    /** ConnectivityManager callback (API 24+, named static nested) */
    private final ConnectivityManager.NetworkCallback networkCallback = new PollNetworkCallback(this);

    // ---- Service lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "PollScheduler onCreate");
        createNotificationChannels();
        startForeground();

        // Register receivers
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        // vc31: Android 14+ (targetSdk>=34) requires RECEIVER_EXPORTED/NOT_EXPORTED
        // for dynamic receivers on non-system-protected actions; missing flag throws
        // SecurityException -> service never starts. System broadcasts (screen on/off,
        // connectivity) are exempt, but we pass the flag uniformly for consistency.
        IntentFilter alarmFilter = new IntentFilter();
        alarmFilter.addAction("com.pocketorca.chat.POLL_ALARM");
        IntentFilter netFilter = new IntentFilter();
        netFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerLocal(screenReceiver, screenFilter);
        registerLocal(alarmReceiver, alarmFilter);
        registerLocal(networkReceiver, netFilter);

        // API 24+ network callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(networkCallback);
        }

        // vc29: load persisted interval + init exact alarm driver
        loadIntervalPref();
        alarmMgr = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent alarmIntent = new Intent("com.pocketorca.chat.POLL_ALARM")
                .setPackage(getPackageName());
        alarmPi = PendingIntent.getBroadcast(this, 42, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Start polling
        startPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running.compareAndSet(false, true)) {
            Log.i(TAG, "Service started (START_STICKY)");
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "Service onDestroy — will restart (START_STICKY)");
        running.set(false);
        scheduler.shutdownNow();
        // vc29: cancel exact alarm
        if (alarmMgr != null && alarmPi != null) {
            try { alarmMgr.cancel(alarmPi); } catch (Exception ignore) {}
        }

        try { unregisterReceiver(screenReceiver); } catch (Exception ignore) {}
        try { unregisterReceiver(alarmReceiver); } catch (Exception ignore) {}
        try { unregisterReceiver(networkReceiver); } catch (Exception ignore) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignore) {}
        }

        super.onDestroy();
    }

    /** vc31: register receiver with RECEIVER_NOT_EXPORTED on API 33+; two-arg fallback below. */
    private void registerLocal(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }

    // ---- Notification channels ----

    private void createNotificationChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Foreground channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // vc11 fix: channel group must exist BEFORE channels reference it,
            // otherwise NotificationManager.createNotificationChannel throws
            // IllegalArgumentException ("NotificationChannelGroup doesn't exist")
            // and the service crashes on create (vc11 release blocker).
            NotificationChannelGroup group = new NotificationChannelGroup(
                    NOTIF_CHANNEL_ID, "消息通知");
            nm.createNotificationChannelGroup(group);

            NotificationChannel fgCh = new NotificationChannel(
                    FG_CHANNEL_ID, FG_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            fgCh.setShowBadge(false);
            nm.createNotificationChannel(fgCh);

            // Message notification channel (per-contact groups)
            // vc18: IMPORTANCE_HIGH → heads-up 横幅弹出（微信体验）；
            // 渠道 importance 创建后不可变，故用新 ID，旧 DEFAULT 渠道删除
            nm.deleteNotificationChannel(NOTIF_CHANNEL_ID);
            NotificationChannel msgCh = new NotificationChannel(
                    NOTIF_CHANNEL_ID_V2, NOTIF_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            msgCh.setGroup(NOTIF_CHANNEL_ID);
            // vc20: 显式开声音+震动，不依赖系统渠道默认值
            msgCh.enableVibration(true);
            msgCh.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
            nm.createNotificationChannel(msgCh);

            // Approval notification channel
            NotificationChannel approvalCh = new NotificationChannel(
                    APPROVAL_CHANNEL_ID, APPROVAL_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            approvalCh.setGroup(NOTIF_CHANNEL_ID);
            nm.createNotificationChannel(approvalCh);
        }
    }

    // ---- Foreground notification ----

    private void startForeground() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, FG_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("PocketOrca-Chat")
                .setContentText("保持连接")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setShowWhen(false);

        startForeground(1, builder.build());
    }

    private String fgBaseText = "保持连接";

    /** vc26: run activity indicator on the persistent notification
     *  ("正在处理…" / "正在使用工具 X" / "" → back to 保持连接).
     *  Re-notify id 1 without calling startForeground again (already foreground). */
    void updateRunActivity(String text) {
        String t = (text == null || text.isEmpty()) ? fgBaseText : text + "（点按查看）";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, FG_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("PocketOrca-Chat")
                .setContentText(t)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setShowWhen(false);
        nm.notify(1, builder.build());
    }

    // ---- Poll scheduling (vc29: 精确闹钟驱动，穿透 Doze) ----

    private void startPolling() {
        if (alarmMgr == null || alarmPi == null) return;
        long interval = clampInterval(userIntervalMs);
        // setExactAndAllowWhileIdle：熄屏/Doze 下也按时触发（每 app 最低 9s 配额，
        // 30s 档远在配额内）。亮屏时同样走闹钟，行为一致免分档困惑。
        alarmMgr.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + interval,
                alarmPi);
        Log.d(TAG, "Alarm armed, interval=" + interval / 1000 + "s");
    }

    private void reschedulePoll() {
        startPolling();
    }

    private void loadIntervalPref() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            userIntervalMs = clampInterval(sp.getLong(PREF_INTERVAL, POLL_DEFAULT_MS));
        } catch (Exception e) {
            userIntervalMs = POLL_DEFAULT_MS;
        }
    }

    /** vc29: JS 设置面板改档 → 持久化 + 立即重排。 */
    public void setUserInterval(long ms) {
        userIntervalMs = clampInterval(ms);
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(PREF_INTERVAL, userIntervalMs).apply();
        } catch (Exception ignore) {}
        Log.i(TAG, "User interval → " + userIntervalMs / 1000 + "s");
        reschedulePoll();
    }

    public long getUserInterval() {
        return userIntervalMs;
    }

    private static long clampInterval(long ms) {
        if (ms < MIN_INTERVAL_MS) return MIN_INTERVAL_MS;
        if (ms > MAX_INTERVAL_MS) return MAX_INTERVAL_MS;
        return ms;
    }

    /** Immediate poll triggered by screen-on / network change. */
    private void triggerImmediatePoll() {
        scheduler.execute(new ImmediatePollTask(this));
    }

    // ---- Core poll logic ----

    private void doPoll() {
        if (!running.get()) return;
        if (!pollInFlight.compareAndSet(false, true)) return;   // 上一轮未完，跳过
        try {
            doPollInner();
        } finally {
            pollInFlight.set(false);
            // vc29: one-shot 闹钟模式——每轮结束排下一轮
            reschedulePoll();
        }
    }

    private void doPollInner() {

        JSONArray contacts;
        try {
            contacts = new JSONArray(Store.listContacts());
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse contacts", e);
            return;
        }
        if (contacts.length() == 0) return;

        int added = 0;
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject c = contacts.optJSONObject(i);
            if (c == null) continue;
            String cid = c.optString("id", "");
            if (cid.isEmpty()) continue;
            String name = c.optString("name", "");
            if (!name.isEmpty()) sessionNameCache.put(cid, name);
            try {
                added += pullOwnSession(cid);
            } catch (Exception e) {
                Log.e(TAG, "poll failed for " + cid, e);
            }
        }

        if (added > 0) {
            pendingMessageCount += added;
            sendAggregatedNotification();
        }
        checkApprovalTimeouts();
    }

    /** vc18: 记录待通知的最后一条预览（按联系人） */
    void recordPendingPreview(String cid, String content) {
        if (content == null || content.isEmpty()) return;
        String one = content.replace('\n', ' ');
        if (one.length() > 80) one = one.substring(0, 80) + "…";
        pendingPreview.put(cid, one);
    }

    /**
     * vc16: pull ONLY the session this contact is bound to, and only messages
     * that were never seen before.
     *
     * vc15 及之前：doPoll 列出服务端全部会话（limit 200），首轮 prevActive==null
     * 让每个会话都算“有变化”，逐个 GET messages（每个 ≤500 条）并无条件
     * ChatDB.insert 进同一个联系人 —— 8T 上几十个历史 agent 会话一次性灌成
     * 2800 条；注释声称“按 id 去重”，代码里没有任何去重，每轮还会重复追加。
     *
     * 现在：①只认 contacts 自己的 session_id（ChatDB.getSession），没有就不拉
     * ②首次见到该会话只建基线（记下全部 mid，不写库），避免历史倒灌
     * ③之后逐条 markSeen(mid，唯一索引兜底)，只有首次见到的 mid 才写库并计数
     */
    private int pullOwnSession(String cid) {
        String sid = ChatDB.getSession(cid);
        if (sid == null || sid.isEmpty()) return 0;   // 无会话指针 → 不猜、不拉

        String resp = HermesAdapter.getMessages(cid, sid);
        JSONObject obj;
        try { obj = new JSONObject(resp); } catch (Exception e) { return 0; }
        if (obj.optInt("http", 0) != 200) return 0;

        JSONArray messages = messagesOf(obj);
        if (messages == null || messages.length() == 0) return 0;

        // 首次同步：只建基线，不写库（历史倒灌 = vc15 的 2800 条事故）
        if (!ChatDB.isSynced(cid, sid)) {
            int seeded = 0;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null) continue;
                String mid = m.optString("id", "");
                if (mid.isEmpty()) continue;
                ChatDB.markSeen(cid, sid, mid);
                seeded++;
            }
            ChatDB.markSynced(cid, sid);
            Log.i(TAG, "poll baseline " + cid + "/" + sid + ": " + seeded + " historical msgs skipped");
            return 0;
        }

        int added = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null) continue;
            String mid = msg.optString("id", "");
            String role = msg.optString("role", "");
            if (mid.isEmpty() || role.isEmpty()) continue;

            String content = contentOf(msg);
            if (content.isEmpty()) continue;

            String mapped = role.equals("user") ? "user" : "bot";
            if (!ChatDB.markSeen(cid, sid, mid)) continue;   // 幂等：这条处理过

            // vc19: 只放行 user/assistant，tool/system 等中间行 markSeen 后跳过，
            // 不入库不通知（服务端 transcript 原样返回 role=tool 消息，
            // 旧逻辑把 {"output":...} 工具结果渲染成 bot 气泡）
            boolean isAssistant = "assistant".equals(role);
            if (!"user".equals(role) && !isAssistant) continue;
            // vc19: 带 tool_calls 的 assistant 是中间过程话（"我先看一下代码"），轮询版
            // 无流式，只留最终回复最干净
            if (isAssistant && msg.optJSONArray("tool_calls") != null) continue;

            // vc25: 前台也通知——去掉 hasRecent 30min 抑制（连真新消息一起吞）。
            // 幂等由 markSeen(mid) + insert 返回 -1（mid 唯一索引 CONFLICT_IGNORE）兜底。
            if (ChatDB.insert(cid, mapped, content, mid) <= 0) continue;
            added++;

            // vc18: 只有真正推给用户的 bot 消息才进通知预览
            if (!"user".equals(mapped)) {
                recordPendingPreview(cid, content);
            }

            JSONObject event = new JSONObject();
            try {
                event.put("event", "poll.message");
                JSONObject payload = new JSONObject();
                payload.put("sessionId", sid);
                payload.put("contactId", cid);
                payload.put("messageId", mid);
                payload.put("role", role);
                payload.put("content", content);
                event.put("payload", payload);
            } catch (Exception ignore) {}
            MainActivity.pushEventToJs(event.toString());
        }
        return added;
    }

    private static JSONArray messagesOf(JSONObject envelope) {
        try {
            Object body = envelope.opt("body");
            if (body instanceof String) return new JSONObject((String) body).optJSONArray("data");
            if (body instanceof JSONObject) return ((JSONObject) body).optJSONArray("data");
        } catch (Exception ignore) {}
        return null;
    }

    private static String contentOf(JSONObject msg) {
        String content = msg.optString("content", "");
        if (!content.isEmpty()) return content;
        JSONObject o = msg.optJSONObject("content");
        if (o != null) return o.optString("text", "");
        return "";
    }

    /** Check for approval requests that have timed out (120s). */
    private void checkApprovalTimeouts() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : approvalRequests.entrySet()) {
            long elapsed = now - entry.getValue();
            if (elapsed > APPROVAL_TIMEOUT_MS) {
                String cid = entry.getKey();
                String name = sessionNameCache.getOrDefault(cid, "Agent");
                Log.w(TAG, "Approval timeout for " + name + " (elapsed=" + elapsed / 1000 + "s)");

                // Notify JS about timeout
                JSONObject event = new JSONObject();
                try {
                    event.put("event", "approval.timeout");
                    JSONObject payload = new JSONObject();
                    payload.put("contactId", cid);
                    payload.put("contactName", name);
                    event.put("payload", payload);
                } catch (Exception ignore) {}
                MainActivity.pushEventToJs(event.toString());

                // Send notification
                sendApprovalNotification(cid, name, true);

                // Remove from tracking
                approvalRequests.remove(cid);
            }
        }
    }

    /** Register an approval request for timeout tracking. */
    public void registerApprovalRequest(String cid) {
        approvalRequests.put(cid, System.currentTimeMillis());
    }

    /** Clear an approval request (resolved). */
    public void clearApprovalRequest(String cid) {
        approvalRequests.remove(cid);
    }

    // ---- Notification dispatch ----

    /**
     * vc23: 逐联系人子通知 + 分组 summary。
     *
     * vc18/vc20 的横幅修复全落在死代码上：渠道 pocketorca_msgs_v2（HIGH）建了，
     * 但本方法仍用 NOTIF_CHANNEL_ID 旧的 "pocketorca_msgs" 发通知，而该渠道在
     * createNotificationChannels() 里每次启动都被 deleteNotificationChannel 删掉
     * → Android 8+ 语义=渠道不存在，通知直接丢弃，横幅无从谈起。
     * vc20 的 enableVibration/setSound 也一并挂在没人用的 V2 渠道上。
     *
     * vc23 修：①严格用 NOTIF_CHANNEL_ID_V2 ②改 per-contact 子通知（微信式，
     * 每个联系人一条独立通知，点进对应会话）③summary 只汇总、静默不重复提醒
     * ④加 CATEGORY_MESSAGE + DEFAULT_ALL，亮屏时按消息类通知弹横幅。
     */
    private void sendAggregatedNotification() {
        if (pendingMessageCount <= 0) return;

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // ① 每个联系人一条子通知（横幅由子通知触发）
        for (Map.Entry<String, String> e : pendingPreview.entrySet()) {
            String cid = e.getKey();
            String name = sessionNameCache.getOrDefault(cid, cid);

            Intent ci = new Intent(this, MainActivity.class);
            ci.putExtra("openContactId", cid);
            PendingIntent cpi = PendingIntent.getActivity(
                    this, 100 + Math.abs(cid.hashCode() % 900), ci,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder child;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                child = new Notification.Builder(this, NOTIF_CHANNEL_ID_V2);
            } else {
                child = new Notification.Builder(this);
            }
            child.setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(name)
                    .setContentText(e.getValue())
                    .setContentIntent(cpi)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setOnlyAlertOnce(false)
                    .setGroup(NOTIF_CHANNEL_ID)
                    .setGroupSummary(false);

            nm.notify(msgNotifId(cid), child.build());
        }

        // ② 汇总条：静默（横幅已在子通知上弹过，别响两次）
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // vc18: 标题/正文带联系人名+消息预览（多条时显示最近联系人+计数）
        String title = "PocketOrca-Chat";
        String text = pendingMessageCount + " 条新消息";
        if (!pendingPreview.isEmpty()) {
            String lastCid = pendingPreview.keySet().iterator().next();
            String name = sessionNameCache.getOrDefault(lastCid, lastCid);
            title = name;
            text = pendingPreview.get(lastCid);
            if (pendingMessageCount > 1) text = text + "（共 " + pendingMessageCount + " 条）";
        }

        Notification.Builder sum;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sum = new Notification.Builder(this, NOTIF_CHANNEL_ID_V2);
            // 汇总条静默：只让子通知发声/弹横幅（setSilent 在被裁剪的 stub jar 里没有）
            sum.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
        } else {
            sum = new Notification.Builder(this);
        }
        sum.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setGroup(NOTIF_CHANNEL_ID)
                .setGroupSummary(true)
                .setOnlyAlertOnce(true);

        nm.notify(2, sum.build());
        pendingMessageCount = 0;
        pendingPreview.clear();
    }

    /** per-contact 子通知 ID：1000 起，避开 1/2/3 保留位 */
    static int msgNotifId(String cid) {
        return 1000 + Math.abs(cid.hashCode() % 100000);
    }

    private void sendApprovalNotification(String cid, String name, boolean expired) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent notifIntent = new Intent(this, MainActivity.class);
        notifIntent.putExtra("openContactId", cid);
        PendingIntent pi = PendingIntent.getActivity(
                this, 3, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, APPROVAL_CHANNEL_ID)
                    .setChannelId(APPROVAL_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        String title = expired ? "审批已过期" : "审批请求";
        String text = expired
                ? name + " 的审批已超时，请重新发起"
                : name + " 请求审批";

        builder.setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("PocketOrca-Chat")
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH);

        nm.notify(3, builder.build());
    }

    // ---- Doze / Battery Optimisation Guide ----

    /**
     * Check if app is in battery optimisation whitelist and guide user if not.
     * Returns true if already whitelisted or user dismissed the guide.
     */
    public boolean checkBatteryOptimisation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;

        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return false;

        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return true;
        }

        // Not whitelisted — launch guide intent
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        return false;
    }

    // ---- Binder ----

    public static class LocalBinder extends Binder {
        private final java.lang.ref.WeakReference<PollScheduler> svc;
        LocalBinder(PollScheduler s) { svc = new java.lang.ref.WeakReference<>(s); }
        public PollScheduler getService() { return svc.get(); }
    }

    /**
     * Get current poll interval (for testing/debugging).
     */
    public long getCurrentInterval() {
        return userIntervalMs;
    }

    /**
     * Get screen state (for testing).
     */
    public boolean isScreenOn() {
        return isScreenOn.get();
    }

    // ---- Named static nested classes (bt34 d8 匿名类 NPE 铁律，vc72 实锤) ----

    /** vc29: exact alarm tick → run one poll round. */
    private static class AlarmReceiver extends BroadcastReceiver {
        private final java.lang.ref.WeakReference<PollScheduler> svc;
        AlarmReceiver(PollScheduler s) { svc = new java.lang.ref.WeakReference<>(s); }
        @Override
        public void onReceive(Context ctx, Intent intent) {
            PollScheduler s = svc.get();
            if (s == null) return;
            Log.d(TAG, "POLL_ALARM tick");
            s.triggerImmediatePoll();
        }
    }

    /** Screen on/off receiver. */
    private static class ScreenReceiver extends BroadcastReceiver {
        private final java.lang.ref.WeakReference<PollScheduler> svc;
        ScreenReceiver(PollScheduler s) { svc = new java.lang.ref.WeakReference<>(s); }
        @Override
        public void onReceive(Context ctx, Intent intent) {
            PollScheduler s = svc.get();
            if (s == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                s.isScreenOn.set(true);
                // vc29: 亮熄屏同一档（用户设置），亮屏只做立即轮询补一轮
                Log.d(TAG, "Screen ON → immediate poll");
                s.triggerImmediatePoll();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                s.isScreenOn.set(false);
                // vc29: 熄屏不改档（闹钟 ELAPSED_REALTIME_WAKEUP 自带唤醒），
                // 但熄屏瞬间排下一轮闹钟，防止 Doze 推迟刚 arm 的闹钟基准
                s.reschedulePoll();
            }
        }
    }

    /** Network change broadcast receiver (legacy, pre-API24 path). */
    private static class NetworkReceiver extends BroadcastReceiver {
        private final java.lang.ref.WeakReference<PollScheduler> svc;
        NetworkReceiver(PollScheduler s) { svc = new java.lang.ref.WeakReference<>(s); }
        @Override
        public void onReceive(Context ctx, Intent intent) {
            PollScheduler s = svc.get();
            if (s == null) return;
            Log.d(TAG, "Network change → immediate poll");
            s.triggerImmediatePoll();
        }
    }

    /** ConnectivityManager.NetworkCallback (API 24+). */
    private static class PollNetworkCallback extends ConnectivityManager.NetworkCallback {
        private final java.lang.ref.WeakReference<PollScheduler> svc;
        PollNetworkCallback(PollScheduler s) { svc = new java.lang.ref.WeakReference<>(s); }
        @Override
        public void onAvailable(Network network) {
            PollScheduler s = svc.get();
            if (s == null) return;
            Log.d(TAG, "Network available → immediate poll");
            s.triggerImmediatePoll();
        }
    }

    /** Immediate poll task. */
    private static class ImmediatePollTask implements Runnable {
        private final java.lang.ref.WeakReference<PollScheduler> svc;
        ImmediatePollTask(PollScheduler s) { svc = new java.lang.ref.WeakReference<>(s); }
        @Override
        public void run() {
            PollScheduler s = svc.get();
            if (s == null) return;
            s.doPoll();
        }
    }

    // ---- Pure decision functions (extracted for JVM testing) ----

    /**
     * vc29: 亮熄屏同档，间隔来自用户设置。
     * @deprecated 保留纯函数给 JVM 测试；真实间隔以 getUserInterval() 为准
     */
    public static long determinePollInterval(boolean screenOn, long userIntervalMs) {
        return userIntervalMs;
    }

    /**
     * Determine action based on network state.
     * @param hasNetwork true if network is available
     * @return "immediate" if network just available, "schedule" if has network, "none" if no network
     */
    public static String determineNetworkAction(boolean hasNetwork, boolean wasConnected) {
        if (!hasNetwork) {
            return "none";
        }
        if (wasConnected && !hasNetwork) {
            // Transition: was connected, now disconnected — do nothing special
            return "schedule";
        }
        if (!wasConnected && hasNetwork) {
            // Network just recovered
            return "immediate";
        }
        return "schedule";
    }

    /**
     * Check if a screen event is a duplicate (should not trigger reschedule).
     * @param currentEvent current screen event action
     * @param lastEvent last screen event action
     * @return true if this is a duplicate event that should be ignored
     */
    public static boolean isDuplicateScreenEvent(String currentEvent, String lastEvent) {
        if (lastEvent == null) return false;
        return currentEvent.equals(lastEvent);
    }
}
