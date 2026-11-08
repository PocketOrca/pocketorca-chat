package com.pocketorca.chat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * vc21: save a base64 data-URL image (bot MEDIA: bubble) into the system gallery
 * (MediaStore Pictures/PocketOrca). Named static class only (d8 铁律).
 * API 29+ (minSdk 29): MediaStore writes need no runtime permission.
 */
final class MediaSaver {
    private MediaSaver() {}

    /** @return {"ok":true,"uri":"..."} or {"ok":false,"error":"..."} */
    static String saveFromDataUrl(Object ctxObj, String dataUrl) {
        Context_ ctx = null;
        try {
            ctx = new Context_((android.content.Context) ctxObj);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"bad context\"}";
        }
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) {
            return "{\"ok\":false,\"error\":\"not an image data url\"}";
        }
        String mime = "image/png";
        int semi = dataUrl.indexOf(';');
        if (semi > 5 && dataUrl.charAt(4) == ':') mime = dataUrl.substring(5, semi);
        int b64start = dataUrl.indexOf("base64,");
        if (b64start < 0) return "{\"ok\":false,\"error\":\"no base64 payload\"}";
        byte[] raw;
        try {
            raw = android.util.Base64.decode(dataUrl.substring(b64start + 7), android.util.Base64.DEFAULT);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"base64 decode failed\"}";
        }
        if (raw.length == 0) return "{\"ok\":false,\"error\":\"empty image\"}";

        // Decode+re-encode to guarantee a format MediaStore can render; fall back
        // to raw bytes if decode fails (webp decode is supported since API 29).
        String ext = extOf(mime);
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        byte[] out = raw;
        String outMime = mime;
        if (bmp != null && "image/webp".equals(mime)) {
            // webp albums render inconsistently on some skins — normalize to PNG
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            out = bos.toByteArray();
            outMime = "image/png";
            ext = "png";
            bmp = null;
        }

        ContentValues v = new ContentValues();
        long now = System.currentTimeMillis();
        v.put(MediaStore.Images.Media.DISPLAY_NAME,
                "PocketOrca_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now)) + "." + ext);
        v.put(MediaStore.Images.Media.MIME_TYPE, outMime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketOrca");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        ContentResolver cr = ctx.get().getContentResolver();
        try {
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return "{\"ok\":false,\"error\":\"mediastore insert failed\"}";
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) return "{\"ok\":false,\"error\":\"output stream null\"}";
            os.write(out);
            os.flush();
            os.close();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            }
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private static String extOf(String mime) {
        if ("image/jpeg".equals(mime)) return "jpg";
        if ("image/gif".equals(mime)) return "gif";
        if ("image/webp".equals(mime)) return "webp";
        return "png";
    }

    /** Named holder (d8-safe) so the bridge call site reads cleanly. */
    static final class Context_ {
        private final android.content.Context c;
        Context_(android.content.Context c) { this.c = c; }
        android.content.Context get() { return c; }
    }
}
