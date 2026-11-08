package com.pocketorca.chat;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Multi-contact endpoint store (M3): contacts.json in filesDir.
 * Shape: {"contacts":[{"id","name","url"}...]}
 * Migration: legacy single-endpoint endpoint.json ({url,token}) imported once as first contact.
 * Token encrypted via CredentialVault (Android Keystore), M4.
 */
final class Store {
    private Store() {}

    static synchronized String listContacts() {
        JSONArray arr = load().optJSONArray("contacts");
        return arr == null ? "[]" : arr.toString();
    }

    /** vc15: number of stored contacts (data panel / stats). */
    static synchronized int count() {
        JSONArray arr = load().optJSONArray("contacts");
        return arr == null ? 0 : arr.length();
    }

    /** vc15: drop all contacts + vault entries (local-data wipe, pairs with
     *  ChatDB.wipeAll()). Server-side sessions are untouched. */
    static synchronized boolean wipeAll() {
        try {
            JSONArray arr = load().optJSONArray("contacts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c != null) CredentialVault.removeToken(appContext(), c.optString("id", ""));
                }
            }
            JSONObject root = new JSONObject();
            root.put("contacts", new JSONArray());
            save(root);
            return true;
        } catch (Exception e) { return false; }
    }

    static synchronized String addContact(String json) {
        try {
            JSONObject in = new JSONObject(json);
            String url = in.optString("url", "").trim().replaceAll("/+$", "");
            String token = in.optString("token", "");
            String name = in.optString("name", "").trim();
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                return err("URL 需以 http(s):// 开头");
            if (name.isEmpty()) name = hostOf(url);
            JSONObject root = load();
            JSONArray arr = root.optJSONArray("contacts");
            if (arr == null) arr = new JSONArray();
            String id = "c" + System.currentTimeMillis();
            JSONObject item = new JSONObject();
            item.put("id", id); item.put("name", name); item.put("url", url);
            // M4: token encrypted via CredentialVault
            if (!token.isEmpty()) {
                CredentialVault.saveToken(appContext(), id, token);
            }
            arr.put(item);
            root.put("contacts", arr);
            save(root);
            return ok(id);
        } catch (Exception e) { return err(e.getMessage()); }
    }

    static synchronized String updateContact(String id, String json) {
        try {
            JSONObject in = new JSONObject(json);
            JSONObject item = find(id);
            if (item == null) return err("联系人不存在");
            String url = in.optString("url", item.optString("url")).trim().replaceAll("/+$", "");
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                return err("URL 需以 http(s):// 开头");
            item.put("url", url);
            String newToken = in.optString("token", item.optString("token"));
            // M4: token encrypted via CredentialVault
            if (!newToken.isEmpty()) {
                CredentialVault.saveToken(appContext(), id, newToken);
            }
            save(load());
            return ok(id);
        } catch (Exception e) { return err(e.getMessage()); }
    }

    static synchronized String removeContact(String id) {
        try {
            JSONObject root = load();
            JSONArray arr = root.optJSONArray("contacts");
            if (arr == null) return ok(id);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c != null && !id.equals(c.optString("id"))) out.put(c);
            }
            root.put("contacts", out);
            // M4: remove encrypted token from vault
            CredentialVault.removeToken(appContext(), id);
            save(root);
            return ok(id);
        } catch (Exception e) { return err(e.getMessage()); }
    }

    // ---- per-contact credential lookups (HermesAdapter) ----
    static synchronized String url(String id) { JSONObject c = find(id); return c == null ? "" : c.optString("url", ""); }
    // M4: token retrieved from Keystore-backed vault
    static synchronized String token(String id) {
        String tok = CredentialVault.getToken(appContext(), id);
        if (tok != null && !tok.isEmpty()) return tok;
        // Fallback: try contacts.json (pre-M4 or vault corruption)
        JSONObject c = find(id);
        return c == null ? "" : c.optString("token", "");
    }
    static synchronized String name(String id) { JSONObject c = find(id); return c == null ? "" : c.optString("name", ""); }

    // ---- internals ----
    private static JSONObject find(String id) {
        JSONArray arr = load().optJSONArray("contacts");
        if (arr == null || id == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c != null && id.equals(c.optString("id"))) return c;
        }
        return null;
    }

    private static JSONObject load() {
        try {
            if (!file().exists()) migrateLegacy();
            File f = file();
            if (!f.exists()) return new JSONObject();
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf); in.close();
            return new JSONObject(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }

    private static void save(JSONObject root) throws Exception {
        FileOutputStream out = new FileOutputStream(file());
        out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    /** One-time import of M1/M2 single-endpoint endpoint.json as the first contact. */
    private static void migrateLegacy() {
        try {
            File old = new File(appContext().getFilesDir(), "endpoint.json");
            if (!old.exists()) return;
            FileInputStream in = new FileInputStream(old);
            byte[] buf = new byte[(int) old.length()];
            int n = in.read(buf); in.close();
            JSONObject legacy = new JSONObject(new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8));
            String url = legacy.optString("url", "");
            if (!url.startsWith("http://") && !url.startsWith("https://")) return;
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("id", "c" + System.currentTimeMillis());
            item.put("name", hostOf(url));
            item.put("url", url);
            // M4: legacy token encrypted via vault
            String legacyToken = legacy.optString("token", "");
            if (!legacyToken.isEmpty()) {
                CredentialVault.saveToken(appContext(), item.optString("id"), legacyToken);
            }
            arr.put(item);
            root.put("contacts", arr);
            save(root);
        } catch (Exception ignore) {}
    }

    private static String hostOf(String url) {
        try { return new java.net.URL(url).getHost(); }
        catch (Exception e) { return url == null ? "联系人" : url; }
    }

    private static String ok(String id) { return "{\"ok\":true,\"id\":\"" + id + "\"}"; }
    private static String err(String m) { return "{\"ok\":false,\"error\":\"" + esc(m) + "\"}"; }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static File file() { return new File(appContext().getFilesDir(), "contacts.json"); }

    private static Context appContext() {
        try { return (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null); }
        catch (Exception e) { throw new IllegalStateException("no app context"); }
    }

    /** ChatDB needs the app context too (vc6: local chat history, WeChat-style). */
    static Context context() { return appContext(); }
}
