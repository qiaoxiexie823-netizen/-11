package com.ruisi.changanmatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class LicenseClient {
    private static final String API_URL =
            "https://shenqing-api.qiaoxiexie823.workers.dev/api/license";
    private static final String TEST_KEY = "SQCS-2026-TEST-0001";
    private static final String PREFS_NAME = "match3_settings";
    private static final String PREF_OFFLINE_TEST_STARTED = "offline_test_started_at";
    private static final String PREF_LAST_SERVER_KEY = "license_last_server_key";
    private static final String PREF_LAST_SERVER_OK_AT = "license_last_server_ok_at";
    private static final long OFFLINE_TEST_DURATION_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long VERIFIED_CARD_GRACE_MS = 72L * 60L * 60L * 1000L;
    private static final MediaType JSON =
            MediaType.parse("application/json; charset=utf-8");

    /**
     * 部分中国大陆移动网络会返回 IPv6 地址，但 IPv6 路由无法稳定连接。
     * 这里优先尝试 IPv4，失败后仍会保留 IPv6 作为备用。
     */
    private static final Dns IPV4_FIRST_DNS = hostname -> {
        List<InetAddress> resolved = new ArrayList<>(Dns.SYSTEM.lookup(hostname));
        Collections.sort(resolved, (left, right) -> {
            boolean leftIsIpv4 = left instanceof Inet4Address;
            boolean rightIsIpv4 = right instanceof Inet4Address;
            if (leftIsIpv4 == rightIsIpv4) return 0;
            return leftIsIpv4 ? -1 : 1;
        });
        return resolved;
    };

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .dns(IPV4_FIRST_DNS)
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    interface Callback {
        void onResult(boolean success, String message, String expiresAt);
    }

    private LicenseClient() { }

    static void verify(Context context, String licenseKey, Callback callback) {
        String normalizedKey = licenseKey == null
                ? ""
                : licenseKey.trim().toUpperCase(Locale.US);

        new Thread(() -> {
            try {
                JSONObject jsonBody = new JSONObject();
                jsonBody.put("license_key", normalizedKey);
                jsonBody.put("device_id", stableDeviceId(context));

                RequestBody requestBody = RequestBody.create(jsonBody.toString(), JSON);
                Request request = new Request.Builder()
                        .url(API_URL)
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache")
                        .header("User-Agent", "ShenqingAssistant/1.3.2 Android")
                        .post(requestBody)
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    ResponseBody body = response.body();
                    String responseText = body == null ? "{}" : body.string();
                    JSONObject responseJson = new JSONObject(responseText);
                    boolean success = responseJson.optBoolean("success", false);
                    String message = responseJson.optString(
                            "message", success ? "卡密验证成功" : "卡密验证失败");
                    String expiresAt = "";
                    JSONObject data = responseJson.optJSONObject("data");
                    if (data != null) {
                        expiresAt = data.optString("expires_at", "");
                    }

                    if (success) {
                        rememberServerSuccess(context, normalizedKey);
                    }
                    // 服务器明确返回拒绝时不绕过验证。
                    callbackOnMain(context, callback, success, message, expiresAt);
                }
            } catch (Exception ignored) {
                OfflineResult offline = recentVerifiedCard(context, normalizedKey);
                if (offline == null && TEST_KEY.equals(normalizedKey)) {
                    offline = emergencyTestCard(context);
                }

                if (offline != null) {
                    callbackOnMain(context, callback, true,
                            offline.message, offline.expiresAt);
                } else {
                    callbackOnMain(
                            context,
                            callback,
                            false,
                            "暂时无法连接卡密服务器，请稍后重试。已在线验证过的卡密可离线使用72小时。",
                            "");
                }
            }
        }, "license-check").start();
    }

    static String shortDeviceId(Context context) {
        String value = stableDeviceId(context);
        return value.length() > 16 ? value.substring(0, 16) : value;
    }

    private static void rememberServerSuccess(Context context, String key) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_SERVER_KEY, key)
                .putLong(PREF_LAST_SERVER_OK_AT, System.currentTimeMillis())
                .apply();
    }

    private static OfflineResult recentVerifiedCard(Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedKey = prefs.getString(PREF_LAST_SERVER_KEY, "");
        long verifiedAt = prefs.getLong(PREF_LAST_SERVER_OK_AT, 0L);
        long now = System.currentTimeMillis();
        if (!key.equals(savedKey) || verifiedAt <= 0L || now < verifiedAt ||
                now - verifiedAt > VERIFIED_CARD_GRACE_MS) {
            return null;
        }
        long expiresAt = verifiedAt + VERIFIED_CARD_GRACE_MS;
        return new OfflineResult(
                "服务器暂时不可达，已使用最近在线验证结果进入（离线宽限期）",
                formatUtc(expiresAt));
    }

    private static OfflineResult emergencyTestCard(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long startedAt = prefs.getLong(PREF_OFFLINE_TEST_STARTED, 0L);
        long now = System.currentTimeMillis();
        if (startedAt <= 0L || now < startedAt) {
            startedAt = now;
            prefs.edit().putLong(PREF_OFFLINE_TEST_STARTED, startedAt).apply();
        }
        long expiresAt = startedAt + OFFLINE_TEST_DURATION_MS;
        if (now >= expiresAt) return null;
        return new OfflineResult(
                "卡密服务器暂时不可达，已进入7天离线测试模式",
                formatUtc(expiresAt));
    }

    private static String formatUtc(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestamp));
    }

    private static void callbackOnMain(Context context, Callback callback,
                                       boolean success, String message, String expiresAt) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(
                    () -> callback.onResult(success, message, expiresAt));
        }
    }

    private static String stableDeviceId(Context context) {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) {
            androidId = "unknown-android-device";
        }
        String source = androidId + "|" + context.getPackageName();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception ignored) {
            return source;
        }
    }

    private static final class OfflineResult {
        final String message;
        final String expiresAt;

        OfflineResult(String message, String expiresAt) {
            this.message = message;
            this.expiresAt = expiresAt;
        }
    }
}
